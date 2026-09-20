package eu.inqudium.limesium.common

import ch.qos.logback.classic.Level
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The body-meter cache of the shared metrics owner, driven directly - once here, for both twins: the
 * two ways a cache of meter references can be wrong against a live registry (a removed meter, a
 * rejected id), the lock order its miss path must keep, the meters it must NOT keep, and the key's
 * discrimination. Ported with the cache from legatium's `ClientLoggingMetricsTest`
 * (`docs/assessment/BENCH_REPORT-2026-09-20T10-23-42.md`, section 4.5). The twins' metrics tests keep
 * the lifecycle facts only their filter can show (what the emitter records, when the gauge moves).
 */
class EndpointLoggingMetricsTest {
    @JvmField
    @RegisterExtension
    val metricsLog = CapturedLogger(EndpointLoggingMetrics::class.java.name)

    private val things = "/api/things/{id}"

    /** The owner's two body-meter caches, read through their private fields: the size of the cache is the subject of the leak test. */
    private fun cachedBodyMeters(metrics: EndpointLoggingMetrics): Map<Any, Meter> =
        listOf("bodySizeSummaries", "readStateCounters")
            .flatMap { field ->
                @Suppress("UNCHECKED_CAST")
                (
                    EndpointLoggingMetrics::class.java
                        .getDeclaredField(field)
                        .apply { isAccessible = true }
                        .get(metrics) as Map<Any, Meter>
                ).entries
            }.associate { it.key to it.value }

    /**
     * ConcurrentHashMap's bin of the body-meter key in a default-sized table: the spread of its hash over 16 bins. The
     * owner's private key class is instantiated reflectively, so the hash is the real one.
     */
    private fun bodyMeterBin(
        meterName: String,
        template: String,
    ): Int {
        val key =
            Class
                .forName("eu.inqudium.limesium.common.EndpointLoggingMetrics\$BodyMeterKey")
                .getDeclaredConstructor(String::class.java, String::class.java, String::class.java)
                .apply { isAccessible = true }
                .newInstance(meterName, template, null)
        val hash = key.hashCode()
        return (hash xor (hash ushr 16)) and 15
    }

    /** A handler pattern whose body-meter key lands in the same bin as the one under [template] - the precondition of the lock-order deadlock. */
    private fun templateSharingTheBinOf(
        meterName: String,
        template: String,
    ): String {
        val target = bodyMeterBin(meterName, template)
        return generateSequence(0) { it + 1 }.map { "/api/peer-$it/{id}" }.first { bodyMeterBin(meterName, it) == target }
    }

    private fun summary(
        registry: MeterRegistry,
        meterName: String,
        template: String,
    ): DistributionSummary = registry.get(meterName).tag("uri", template).summary()

    @Test
    fun `should register a body meter anew after the host removed it instead of recording into the detached one`() {
        // What is tested: the cache's one drift case - the owner resolves the body meters once per tag
        //   set, and a host may remove a meter from its registry afterwards.
        // Success criteria: after the removal the next sample lands in a NEW summary the registry holds
        //   (count 1, the new amount), the removed instance saw nothing more; the same for the
        //   read-state counter.
        // Why it matters: a cached reference to a removed meter would count every later exchange into
        //   an instance no exporter reads - silent loss of exactly the opt-in measurement.
        // Given: a sample and a read state recorded, both meters then removed by the host
        val registry = SimpleMeterRegistry()
        val metrics = EndpointLoggingMetrics.forRegistry(registry, EndpointLoggingMetrics.OUTCOME_TIMEOUT)
        metrics.requestBodySize(things, 5)
        metrics.requestBodyRead(things, BodyReadState.COMPLETE)
        val removedSummary = registry.get(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER).summary()
        val removedCounter = registry.get(EndpointLoggingMetrics.REQUEST_BODY_READ_METER).counter()
        registry.remove(removedSummary)
        registry.remove(removedCounter)

        // When
        metrics.requestBodySize(things, 7)
        metrics.requestBodyRead(things, BodyReadState.COMPLETE)

        // Then
        val summary = registry.get(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER).summary()
        assertThat(summary).isNotSameAs(removedSummary)
        assertThat(summary.count()).isEqualTo(1)
        assertThat(summary.totalAmount()).isEqualTo(7.0)
        assertThat(removedSummary.count()).isEqualTo(1)
        val counter = registry.get(EndpointLoggingMetrics.REQUEST_BODY_READ_METER).counter()
        assertThat(counter).isNotSameAs(removedCounter)
        assertThat(counter.count()).isEqualTo(1.0)
        assertThat(removedCounter.count()).isEqualTo(1.0)
    }

    @Test
    fun `should keep a body meter private with one warning when the host rejects its registration`() {
        // What is tested: registerOrFallback behind the body-meter cache - the request summary's id is
        //   taken by a host gauge, so Micrometer rejects the registration with a different-type error.
        // Success criteria: three samples neither throw nor reach the host (which keeps its gauge and
        //   holds no summary under the id); the module logger carries exactly one WARN naming the meter.
        // Why it matters: the conflict path of the fixed meters is pinned by the twins' metrics tests;
        //   the body meters take it lazily through the cache, and must take it once, not per exchange.
        // Given: the summary's exact id taken by a gauge
        val host: MeterRegistry = SimpleMeterRegistry()
        Gauge
            .builder(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER) { 1.0 }
            .tag("uri", things)
            .register(host)
        val metrics = EndpointLoggingMetrics.forRegistry(host, EndpointLoggingMetrics.OUTCOME_TIMEOUT)

        // When
        val thrown = catchThrowable { repeat(3) { metrics.requestBodySize(things, 5) } }

        // Then
        assertThat(thrown).isNull()
        assertThat(host.find(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER).gauge()).isNotNull()
        assertThat(host.find(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER).summary()).isNull()
        val warnings = metricsLog.events.filter { it.level == Level.WARN }
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single().formattedMessage).contains(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER).contains("kept private")
    }

    @Test
    fun `should resolve a body meter outside the cache's lock so a concurrent host removal cannot deadlock`() {
        // What is tested: the lock order of the class KDoc - Micrometer notifies removal listeners under
        //   its registry lock and the owner's listener takes the cache's bin lock, so the miss path must
        //   register WITHOUT holding a bin lock. A filter's `map` is the hook between the cache miss and
        //   Micrometer's lock (it runs before the lock is taken): from there a second thread removes an
        //   already cached summary whose key shares the new key's bin, and the miss path waits for it.
        // Success criteria: the removal finishes while the registration is pending; the second summary
        //   lands in the host registry (no fallback), and the removed one is registered anew afterwards.
        // Why it matters: resolved inside computeIfAbsent, the two threads held their locks in opposite
        //   order - a hung request thread and a registry frozen for every later registration, from a
        //   component that promises never to disturb the request (legatium's finding 1 of 2026-09-19).
        // Given: two templates whose keys share a bin, a summary cached under the first, the hook armed for the second
        val secondTemplate = templateSharingTheBinOf(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER, things)
        val registry = SimpleMeterRegistry()
        val cached = AtomicReference<DistributionSummary>()
        val removalFinishedInTime = AtomicBoolean(false)
        val remover = Executors.newSingleThreadExecutor()
        registry.config().meterFilter(
            object : MeterFilter {
                override fun map(id: Meter.Id): Meter.Id {
                    if (id.name == EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER && id.getTag("uri") == secondTemplate) {
                        // Between the cache miss and Micrometer's lock: the host removes the cached summary NOW.
                        val removal = remover.submit { registry.remove(cached.get()) }
                        try {
                            removal.get(5, TimeUnit.SECONDS)
                            removalFinishedInTime.set(true)
                        } catch (_: TimeoutException) {
                            // The removing thread is stuck behind a lock this thread holds; proceeding
                            // into Micrometer's lock would complete the deadlock and hang the suite, so
                            // fail the registration instead - registerOrFallback then releases the lock.
                            throw IllegalStateException("the host's removal is stuck behind the cache's lock")
                        }
                    }
                    return id
                }
            },
        )
        try {
            val metrics = EndpointLoggingMetrics.forRegistry(registry, EndpointLoggingMetrics.OUTCOME_TIMEOUT)
            metrics.requestBodySize(things, 5)
            cached.set(summary(registry, EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER, things))

            // When
            metrics.requestBodySize(secondTemplate, 7)
            metrics.requestBodySize(things, 11)

            // Then
            assertThat(removalFinishedInTime).isTrue()
            assertThat(summary(registry, EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER, secondTemplate).totalAmount()).isEqualTo(7.0)
            val reRegistered = summary(registry, EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER, things)
            assertThat(reRegistered).isNotSameAs(cached.get())
            assertThat(reRegistered.totalAmount()).isEqualTo(11.0)
        } finally {
            remover.shutdownNow()
        }
    }

    @Test
    fun `should not cache a body meter the host registry denied`() {
        // What is tested: the cache under a denying MeterFilter (Boot's management.metrics.enable.*, a
        //   tag cap) - Micrometer answers such a registration with a no-op meter it does not store.
        // Success criteria: samples under three templates neither throw nor register a summary in the
        //   host, and the owner's caches hold NO entry for them - only the one accepted response summary.
        // Why it matters: the cache mirrors the registry's entries only for meters the registry holds;
        //   one entry per denied tag set would grow without release exactly in the configuration an
        //   operator chose to bound the meter.
        // Given: the request summary denied, the response summary accepted
        val registry = SimpleMeterRegistry()
        registry.config().meterFilter(MeterFilter.denyNameStartsWith(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER))
        val metrics = EndpointLoggingMetrics.forRegistry(registry, EndpointLoggingMetrics.OUTCOME_CANCELLED)

        // When
        val thrown =
            catchThrowable {
                listOf("a", "b", "c").forEach { metrics.requestBodySize("/api/$it/{id}", 5) }
                metrics.responseBodySize(things, 3)
            }

        // Then
        assertThat(thrown).isNull()
        assertThat(registry.find(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER).summaries()).isEmpty()
        assertThat(cachedBodyMeters(metrics).values).singleElement().satisfies({ assertThat(it.id.name).isEqualTo(EndpointLoggingMetrics.RESPONSE_BODY_SIZE_METER) })
    }

    @Test
    fun `should keep body meters of different templates and states apart`() {
        // What is tested: the cache key's discrimination - the same meter name recorded under two
        //   templates, the read-state counter under two states of one template.
        // Success criteria: two distinct summaries with their own counts (2, 1) and three distinct
        //   read-state counters at 1 each; nothing merged.
        // Why it matters: a key that folded the template or the state would hand the first tag set's
        //   meter to every later one - the per-route distribution the meters promise, silently gone.
        // Given
        val other = "/api/other/{id}"
        val registry = SimpleMeterRegistry()
        val metrics = EndpointLoggingMetrics.forRegistry(registry, EndpointLoggingMetrics.OUTCOME_CANCELLED)

        // When
        metrics.requestBodySize(things, 5)
        metrics.requestBodySize(things, 5)
        metrics.requestBodySize(other, 11)
        metrics.requestBodyRead(things, BodyReadState.UNREAD)
        metrics.requestBodyRead(things, BodyReadState.COMPLETE)
        metrics.requestBodyRead(other, BodyReadState.UNREAD)

        // Then
        assertThat(registry.get(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER).summaries()).hasSize(2)
        assertThat(summary(registry, EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER, things).count()).isEqualTo(2)
        assertThat(summary(registry, EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER, things).totalAmount()).isEqualTo(10.0)
        assertThat(summary(registry, EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER, other).totalAmount()).isEqualTo(11.0)
        assertThat(registry.get(EndpointLoggingMetrics.REQUEST_BODY_READ_METER).counters()).hasSize(3).allSatisfy { assertThat(it.count()).isEqualTo(1.0) }
    }
}
