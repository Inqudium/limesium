package eu.inqudium.limesium.common

import eu.inqudium.limesium.common.EndpointObservationWiring.Observation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition

/**
 * The observation line of the wiring report: every rendering against a bean factory. The tracing class
 * is not on this module's classpath, so the test stands in a class of its own for it - the rendering
 * only asks "does the context hold a bean of that class".
 */
class EndpointObservationWiringTest {
    private val wrapping = Observation("the observation filter is registered at order -2147483647 against this filter's -2147483638", wraps = true)
    private val inside = Observation("the observation filter is registered at order 0 against this filter's -2147483638", wraps = false)

    @Test
    fun `should say that the exchange runs inside the server observation and that handler lines are traced`() {
        // What is tested: describe with a wrapping observation and a tracer bean.
        // Success criteria: the traced line with the twin's placement clause, "inside", and the
        //   consequence for the handler lines and the exchange line.
        // Why it matters: this is the line that tells an operator why handler lines carry a traceId at
        //   all - and that the exchange line's context comes from the header, not from the bridge.
        // Given
        val factory = DefaultListableBeanFactory()
        factory.registerBeanDefinition("tracer", RootBeanDefinition(Class.forName(TRACER)))

        // When
        val line = EndpointObservationWiring.describe(factory, wrapping, tracerClass = TRACER)

        // Then
        assertThat(line).isEqualTo(
            "Endpoint logging found Boot's server observation with Micrometer Tracing - the observation filter is registered at " +
                "order -2147483647 against this filter's -2147483638, so every exchange runs inside the server observation: " +
                "the handler lines carry the bridge's traceId and spanId, the exchange line the trace context of the incoming traceparent",
        )
    }

    @Test
    fun `should say that the exchange runs outside the server span when the observation is ordered behind the filter`() {
        // What is tested: describe with an observation that does NOT wrap the filter, with a tracer.
        // Success criteria: the traced line says "outside" and that the duration is not part of the span.
        // Why it matters: a host that re-registered the filters can push the exchange out of the server
        //   span without noticing; the line is the only place that says so.
        // Given
        val factory = DefaultListableBeanFactory()
        factory.registerBeanDefinition("tracer", RootBeanDefinition(Class.forName(TRACER)))

        // When
        val line = EndpointObservationWiring.describe(factory, inside, tracerClass = TRACER)

        // Then
        assertThat(line)
            .contains("at order 0 against this filter's -2147483638, so the exchange runs outside the server observation and its duration is not part of the server span: ")
            .endsWith("the exchange line the trace context of the incoming traceparent")
    }

    @Test
    fun `should say that only the exchange line carries a trace context without a tracer`() {
        // What is tested: describe with a wrapping observation and no tracer bean - and the same
        //   without wrapping.
        // Success criteria: "but no Micrometer Tracing", measured-not-traced, only the exchange line
        //   carries a trace context; without wrapping, the duration is not part of the measurement.
        // Why it matters: observation without a bridge opens no span and writes no MDC - the operator
        //   must not expect a traceId on handler lines.
        // Given
        val factory = DefaultListableBeanFactory()

        // When
        val wrapped = EndpointObservationWiring.describe(factory, wrapping, tracerClass = TRACER)
        val outside = EndpointObservationWiring.describe(factory, inside, tracerClass = TRACER)

        // Then
        assertThat(wrapped).isEqualTo(
            "Endpoint logging found Boot's server observation but no Micrometer Tracing - the observation filter is registered at " +
                "order -2147483647 against this filter's -2147483638, so every exchange runs inside the server observation: " +
                "exchanges are measured, no server span is opened, and only the exchange line carries a trace context - that of the incoming traceparent",
        )
        assertThat(outside).contains("so the exchange runs outside the server observation and its duration is not part of the measurement: ")
    }

    @Test
    fun `should say that no server observation exists, whatever the tracer or the classpath`() {
        // What is tested: describe without an observation - with a tracer bean, and with a tracer
        //   class that is not on the classpath at all.
        // Success criteria: the no-observation line, identical in both cases; a missing class counts as
        //   a missing bean, no exception.
        // Why it matters: a bridge without the server observation opens no server span - and the
        //   common module must not fail on a classpath without the optional libraries.
        // Given
        val withTracer = DefaultListableBeanFactory()
        withTracer.registerBeanDefinition("tracer", RootBeanDefinition(Class.forName(TRACER)))

        // When
        val line = EndpointObservationWiring.describe(withTracer, null, tracerClass = TRACER)
        val withoutClass = EndpointObservationWiring.describe(DefaultListableBeanFactory(), null, tracerClass = "no.such.Tracer")

        // Then
        assertThat(line).isEqualTo(
            "Endpoint logging found no server observation - Boot's observation auto-configuration is not active " +
                "(no ObservationRegistry bean, or the observation module is absent): exchanges run outside any server observation; " +
                "the exchange line still carries the trace context of an incoming traceparent",
        )
        assertThat(withoutClass).isEqualTo(line)
    }

    private companion object {
        /** A stand-in from this module's classpath for the tracer. */
        const val TRACER = "java.lang.Thread"
    }
}
