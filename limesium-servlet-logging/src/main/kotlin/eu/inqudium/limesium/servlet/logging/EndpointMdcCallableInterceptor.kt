package eu.inqudium.limesium.servlet.logging

import org.slf4j.LoggerFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.context.request.async.CallableProcessingInterceptor
import java.util.concurrent.Callable

/**
 * Restores the `endpoint_*` MDC identity on the Spring MVC async WORKER thread: the filter's chain
 * scope closes as soon as `doFilter` returns, but a `Callable`/`WebAsyncTask` controller keeps working
 * on an MVC task-executor thread afterwards - its application logs carried no correlation keys although
 * the module advertises request identity in MDC while the request is handled (finding 2 of an internal code analysis).
 *
 * Spring MVC invokes [preProcess] on exactly that worker thread immediately before the task and
 * [postProcess] on the same thread immediately after (applied in a `finally` around the invocation), so
 * an [MdcScope] opened and closed there gives the worker the same additive overlay-and-restore
 * semantics as the sync path. The filter registers one instance per request through `WebAsyncUtils` -
 * no `spring-webmvc` dependency, no global MVC configuration, inert for requests that never go async.
 *
 * BOUNDARY: covers the `Callable`/`WebAsyncTask` worker only - `DeferredResult` producers and raw async
 * workers are application-owned threads; see the "MDC coverage" section of [RequestLoggingFilter].
 *
 * FAIL-OPEN: both callbacks confine their own failures and count them as `stage=wiring` - MDC trouble
 * must never disturb async dispatch.
 */
internal class EndpointMdcCallableInterceptor(
    private val exchange: Exchange,
    private val metrics: EndpointLoggingMetrics,
) : CallableProcessingInterceptor {
    // preProcess and postProcess run on the SAME worker thread; the thread-local pairs them without
    // assuming anything about how many workers the executor cycles through.
    private val scope = ThreadLocal<MdcScope?>()

    override fun <T : Any> preProcess(
        request: NativeWebRequest,
        task: Callable<T>,
    ) {
        try {
            scope.set(MdcScope(exchange.requestId, exchange.method, exchange.path))
        } catch (e: Exception) {
            scope.remove()
            reportQuietly {
                metrics.wiringFailure()
                log.debug("Endpoint MDC could not be installed on the async worker; handler logs lose the identity", e)
            }
        }
    }

    override fun <T : Any> postProcess(
        request: NativeWebRequest,
        task: Callable<T>,
        concurrentResult: Any?,
    ) {
        try {
            scope.get()?.close()
        } catch (e: Exception) {
            reportQuietly {
                metrics.wiringFailure()
                log.debug("Endpoint MDC could not be restored on the async worker", e)
            }
        } finally {
            scope.remove()
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(EndpointMdcCallableInterceptor::class.java)
    }
}
