package eu.inqudium.limesium.reactive.logging

import io.micrometer.context.ContextRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.reactor.autoconfigure.ReactorAutoConfiguration
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Hooks
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * The handler-MDC parity contract, driven through BOOT's real configuration path instead of manually
 * enabled hooks (review finding 8): [ReactorAutoConfiguration] is what decides
 * whether `Hooks.enableAutomaticContextPropagation()` runs, and it does so only for
 * `spring.reactor.context-propagation=auto` - the DEFAULT `limited` restores thread-locals around
 * `tap`/`handle` only. Both sides are pinned here: the shipped default does NOT deliver handler MDC in
 * an ordinary operator (the documented prerequisite), and the supported activation mode does, across a
 * real scheduler hop. The hooks AND the registered accessors are global JVM state; the hooks are
 * disabled and the module-owned accessors removed (unless they pre-existed) after every test so no
 * other test inherits them (finding 10 of the internal analysis).
 */
class MdcContextPropagationTest {
    private val contextRunner =
        ReactiveWebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    ReactorAutoConfiguration::class.java,
                    RequestLoggingAutoConfiguration::class.java,
                ),
            ).withBean(NanoTimeSource::class.java, { NanoTimeSource { 0L } })
            .withBean(CorrelationIdGenerator::class.java, { CorrelationIdGenerator { "generated-42" } })
            .withPropertyValues("endpoint-logging.logger-name=http-exchange-reactive-mdc-test")

    private val accessorRegistry = EndpointAccessorRegistryGuard()

    @BeforeEach
    fun setUp() {
        Hooks.disableAutomaticContextPropagation()
        accessorRegistry.snapshot()
        MDC.clear()
    }

    @AfterEach
    fun tearDown() {
        Hooks.disableAutomaticContextPropagation()
        accessorRegistry.restore()
        MDC.clear()
    }

    /** Runs one exchange through the context's filter and returns the MDC observed INSIDE `map`. */
    private fun handlerMdcInPlainOperator(filter: RequestLoggingWebFilter): Pair<Map<String, String?>, String> {
        var handlerMdc: Map<String, String?> = emptyMap()
        var handlerThread = ""
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
        val chain =
            WebFilterChain { ex ->
                Mono
                    .just("probe")
                    .subscribeOn(Schedulers.boundedElastic())
                    .map {
                        handlerThread = Thread.currentThread().name
                        handlerMdc = EndpointMdcContextPropagation.KEYS.associateWith { key -> MDC.get(key) }
                        it
                    }.then(Mono.fromRunnable { ex.response.statusCode = HttpStatus.OK })
            }
        filter.filter(exchange, chain).block()
        return handlerMdc to handlerThread
    }

    @Test
    fun `should register the accessors idempotently under the endpoint keys`() {
        // What is tested: the registrar's idempotence against the JVM-global registry - a context
        //   refresh or a second Spring context must not register duplicates.
        // Success criteria: after registering twice, exactly one accessor per endpoint_* key exists.
        // Why it matters: duplicated accessors would apply and restore the same key twice per operator -
        //   wasted work at best, restore-order surprises at worst.
        // Given/When: registered twice against the JVM-global registry
        EndpointMdcContextPropagation.registerAccessors()
        EndpointMdcContextPropagation.registerAccessors()

        // Then: one accessor per key
        val keys = ContextRegistry.getInstance().threadLocalAccessors.map { it.key() }
        EndpointMdcContextPropagation.KEYS.forEach { key ->
            assertThat(keys.count { it == key }).describedAs("accessors for %s", key).isEqualTo(1)
        }
    }

    @Test
    fun `should not deliver handler MDC under Boot's default limited propagation mode`() {
        // What is tested: the shipped reality of finding 1 (internal analysis) - accessors registered
        //   by the auto-configuration do NOT suffice; Boot's default spring.reactor.context-propagation
        //   mode `limited` never calls Hooks.enableAutomaticContextPropagation(), so an ordinary `map`
        //   operator sees no endpoint_* MDC.
        // Success criteria: with the REAL ReactorAutoConfiguration and no property set, all three
        //   endpoint_* MDC reads inside `map` on a foreign scheduler thread return null.
        // Why it matters: this is the negative boundary the previous test suite could not see - it
        //   manually enabled the hook and thereby proved a setup the module does not ship. This pin
        //   makes the documented `auto` prerequisite regression-visible.
        contextRunner.run { context ->
            // Given: the Boot-configured filter under the DEFAULT propagation mode
            val filter = context.getBean(RequestLoggingWebFilter::class.java)

            // When: an exchange runs with a handler operator on another scheduler thread
            val (handlerMdc, handlerThread) = handlerMdcInPlainOperator(filter)

            // Then: the hop happened, and no identity was restored - the documented default behavior
            assertThat(handlerThread).contains("boundedElastic")
            EndpointMdcContextPropagation.KEYS.forEach { key ->
                assertThat(handlerMdc[key]).describedAs("MDC %s under limited mode", key).isNull()
            }
        }
    }

    @Test
    fun `should restore the exchange identity into handler MDC across a real thread hop in auto mode`() {
        // What is tested: the full parity chain under the SUPPORTED activation mode - Boot's real
        //   ReactorAutoConfiguration reads spring.reactor.context-propagation=auto and enables the
        //   automatic-propagation hook; the filter's contextWrite plus the registered accessors then
        //   restore the identity inside a plain `map` operator.
        // Success criteria: the handler sees all three endpoint_* MDC entries although it runs on a
        //   boundedElastic thread that never had them; the hop is proven by the thread name; nothing
        //   leaks onto the test thread.
        // Why it matters: this is the reactive substitute for the servlet twin's chain-wide MdcScope -
        //   and the proof that the documented prerequisite is sufficient, driven through Boot's own
        //   configuration path instead of a manually enabled hook.
        contextRunner.withPropertyValues("spring.reactor.context-propagation=auto").run { context ->
            // Given: the Boot-configured filter with automatic propagation active
            val filter = context.getBean(RequestLoggingWebFilter::class.java)

            // When: an exchange runs with a handler operator on another scheduler thread
            val (handlerMdc, handlerThread) = handlerMdcInPlainOperator(filter)

            // Then: the identity was visible on the foreign thread
            assertThat(handlerThread).isNotEqualTo(Thread.currentThread().name).contains("boundedElastic")
            assertThat(handlerMdc)
                .containsEntry(MdcKeys.REQUEST_ID, "generated-42")
                .containsEntry(MdcKeys.REQUEST_METHOD, "GET")
                .containsEntry(MdcKeys.ROUTE, "/api/things")

            // And: nothing leaked onto the test thread
            assertThat(MDC.get(MdcKeys.REQUEST_ID)).isNull()
        }
    }
}
