package eu.inqudium.limesium.servlet.logging

import eu.inqudium.limesium.common.EndpointLoggingMetrics
import eu.inqudium.limesium.common.MdcKeys
import eu.inqudium.limesium.common.installMdcAdapter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.slf4j.spi.MDCAdapter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.context.request.ServletWebRequest
import java.util.concurrent.Callable

/**
 * The MVC worker-thread MDC hand-off of [EndpointMdcCallableInterceptor], driven through its own seam:
 * the test thread plays the worker (Spring calls `preProcess` and `postProcess` on the SAME thread, in a
 * `finally`), a failing MDC adapter is installed through the reflective swap like in `MdcScopeTest`, and
 * the fail-open counter is read from a [SimpleMeterRegistry]. The happy path across a real executor is
 * pinned by the container suites (`/it/async`); this class pins the fail-open branches those cannot
 * reach (code analysis of 2026-09-05, finding 6).
 */
class EndpointMdcCallableInterceptorTest {
    private val meterRegistry = SimpleMeterRegistry()
    private val metrics = EndpointLoggingMetrics.forRegistry(meterRegistry, EndpointLoggingMetrics.OUTCOME_TIMEOUT)
    private val webRequest: NativeWebRequest = ServletWebRequest(MockHttpServletRequest())
    private val task = Callable { "result" }
    private lateinit var original: MDCAdapter

    /** Delegates to [delegate]; throws on `put` of the keys in [failPut] and on `remove` of those in [failRemove]. */
    private class FailingAdapter(
        private val delegate: MDCAdapter,
        private val failPut: Set<String> = emptySet(),
        private val failRemove: Set<String> = emptySet(),
    ) : MDCAdapter by delegate {
        override fun put(
            key: String,
            value: String?,
        ) {
            if (key in failPut) throw IllegalStateException("adapter put failed for $key")
            delegate.put(key, value)
        }

        override fun remove(key: String) {
            if (key in failRemove) throw IllegalStateException("adapter remove failed for $key")
            delegate.remove(key)
        }
    }

    @BeforeEach
    fun setUp() {
        original = MDC.getMDCAdapter()
        MDC.clear()
    }

    @AfterEach
    fun tearDown() {
        installMdcAdapter(original)
        MDC.clear()
    }

    private fun exchange(): Exchange =
        Exchange(
            method = "GET",
            path = "/api/things",
            query = null,
            requestId = "corr-1",
            requestHeaders = emptyList(),
            requestCapture = null,
            requestWrapper = null,
            responseCapture = null,
            responseWrapper = null,
            response = MockHttpServletResponse(),
            startNanos = 0L,
        )

    private fun wiringFailures(): Double =
        meterRegistry
            .get(EndpointLoggingMetrics.FAIL_OPEN_METER)
            .tag("stage", "wiring")
            .counter()
            .count()

    @Test
    fun `should overlay the identity on the worker and restore the worker's own MDC afterwards`() {
        // What is tested: the additive overlay-and-restore contract on the worker thread - preProcess
        //   installs the three endpoint_* keys over the worker's ambient MDC, postProcess restores it.
        // Success criteria: between the two calls the identity and the ambient entry are both visible;
        //   after postProcess the ambient entry is intact and no endpoint_* key remains; nothing counted.
        // Why it matters: the worker is a pooled executor thread - a leaked key would attach this
        //   exchange's identity to whatever Callable that thread runs next.
        // Given: a worker thread carrying an ambient entry
        MDC.put("tenant", "acme")
        val interceptor = EndpointMdcCallableInterceptor(exchange(), metrics)

        // When: the task is bracketed by the interceptor
        interceptor.preProcess(webRequest, task)
        val duringTask = listOf(MdcKeys.REQUEST_ID, MdcKeys.REQUEST_METHOD, MdcKeys.ROUTE, "tenant").associateWith { MDC.get(it) }
        interceptor.postProcess(webRequest, task, "result")

        // Then: overlay during the task, exact restoration afterwards
        assertThat(duringTask)
            .containsEntry(MdcKeys.REQUEST_ID, "corr-1")
            .containsEntry(MdcKeys.REQUEST_METHOD, "GET")
            .containsEntry(MdcKeys.ROUTE, "/api/things")
            .containsEntry("tenant", "acme")
        assertThat(MDC.get(MdcKeys.REQUEST_ID)).isNull()
        assertThat(MDC.get(MdcKeys.REQUEST_METHOD)).isNull()
        assertThat(MDC.get(MdcKeys.ROUTE)).isNull()
        assertThat(MDC.get("tenant")).isEqualTo("acme")
        assertThat(wiringFailures()).isEqualTo(0.0)
    }

    @Test
    fun `should let the task run without identity and count stage wiring when the install fails`() {
        // What is tested: the fail-open branch of preProcess - the adapter throws on the third key.
        // Success criteria: neither preProcess nor postProcess throws, the partially installed keys are
        //   rolled back (nothing of the identity remains on the worker), wiring reads 1.
        // Why it matters: MDC trouble must never disturb async dispatch; a throw from preProcess would
        //   fail the Callable before it ran and blame the application.
        // Given: an adapter that fails on endpoint_route
        installMdcAdapter(FailingAdapter(original, failPut = setOf(MdcKeys.ROUTE)))
        val interceptor = EndpointMdcCallableInterceptor(exchange(), metrics)

        // When: the task is bracketed by the interceptor
        val preThrown = catchThrowable { interceptor.preProcess(webRequest, task) }
        val identityDuringTask = MDC.get(MdcKeys.REQUEST_ID)
        val postThrown = catchThrowable { interceptor.postProcess(webRequest, task, "result") }

        // Then: confined, rolled back, counted once
        assertThat(preThrown).isNull()
        assertThat(postThrown).isNull()
        assertThat(identityDuringTask).isNull()
        assertThat(MDC.get(MdcKeys.REQUEST_METHOD)).isNull()
        assertThat(wiringFailures()).isEqualTo(1.0)
    }

    @Test
    fun `should confine a restoration failure and still remove the other keys`() {
        // What is tested: the fail-open branch of postProcess - the adapter refuses to remove the first
        //   key at restoration time.
        // Success criteria: postProcess does not throw, the remaining keys are removed anyway
        //   (best-effort restoration of MdcScope), the loss is counted stage=wiring.
        // Why it matters: a restoration that gave up at the first failure would leave module-owned keys
        //   on the pooled worker for every later key.
        // Given: a scope installed against a healthy adapter, then an adapter failing on the request id
        val interceptor = EndpointMdcCallableInterceptor(exchange(), metrics)
        interceptor.preProcess(webRequest, task)
        installMdcAdapter(FailingAdapter(original, failRemove = setOf(MdcKeys.REQUEST_ID)))

        // When: the worker finishes the task
        val thrown = catchThrowable { interceptor.postProcess(webRequest, task, "result") }

        // Then: nothing escaped, the other keys are gone, the failure is counted
        assertThat(thrown).isNull()
        assertThat(MDC.get(MdcKeys.REQUEST_METHOD)).isNull()
        assertThat(MDC.get(MdcKeys.ROUTE)).isNull()
        assertThat(wiringFailures()).isEqualTo(1.0)
    }
}
