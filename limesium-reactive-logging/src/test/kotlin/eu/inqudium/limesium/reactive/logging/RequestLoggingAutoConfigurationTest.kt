package eu.inqudium.limesium.reactive.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import eu.inqudium.limesium.common.CorrelationIdGenerator
import eu.inqudium.limesium.common.EndpointLoggingMetrics
import eu.inqudium.limesium.common.HeaderValueMasker
import eu.inqudium.limesium.common.MdcKeys
import eu.inqudium.limesium.common.NanoTimeSource
import io.micrometer.context.ContextRegistry
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.slf4j.MDCContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.server.CoWebFilter

/**
 * Contract of the reactive [RequestLoggingAutoConfiguration]: present by default in a REACTIVE web
 * application, removable by the identical `endpoint-logging.enabled` property, every bean overridable.
 * The variant-selection tests run BOTH auto-configurations together - the set the imports resource
 * actually ships - across the meaningful classpath combinations. Kept FLAT - see the Spring Boot test
 * isolation caveat on nested classes.
 */
class RequestLoggingAutoConfigurationTest {
    private val contextRunner =
        ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RequestLoggingAutoConfiguration::class.java))

    /** BOTH shipped auto-configurations, as the imports resource activates them in a real application. */
    private val shippedContextRunner =
        ReactiveWebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    CoRequestLoggingAutoConfiguration::class.java,
                    RequestLoggingAutoConfiguration::class.java,
                ),
            )

    // Nearly every context below activates the Reactor variant and with it the initializer that writes
    // the three accessors into the JVM-global ContextRegistry; the guard restores the registry after
    // EVERY method, not only the one asserting on it.
    private val accessorRegistry = EndpointAccessorRegistryGuard()

    @BeforeEach
    fun setUp() {
        accessorRegistry.snapshot()
    }

    @AfterEach
    fun tearDown() {
        accessorRegistry.restore()
    }

    @Test
    fun `should let a host HeaderValueMasker back the default masker off`() {
        // What is tested: the masker is a @ConditionalOnMissingBean bean like the time source and the
        //   id generator - a host pins its own rendering (a keyed HMAC, a fixed `***`) once.
        // Success criteria: with a host bean the context holds exactly one HeaderValueMasker, the
        //   host's, and the module's default backed off.
        // Why it matters: the properties decide which values are masked; the bean is the host's only
        //   handle on HOW - it must win over the default in the shipped configuration.
        // Given/When: a host masker beside the auto-configuration
        contextRunner.withUserConfiguration(MaskerHostConfig::class.java).run { context ->
            // Then
            assertThat(context).hasSingleBean(HeaderValueMasker::class.java)
            assertThat(context.getBean(HeaderValueMasker::class.java).mask("x")).isEqualTo("***")
        }
    }

    @Test
    fun `should register the web filter and the defaults in a reactive web application`() {
        // What is tested: the auto-configuration alone in a reactive web context.
        // Success criteria: exactly one RequestLoggingWebFilter and one each of the three
        //   injectable defaults.
        // Why it matters: the filter is picked up by WebFlux through its Ordered contract; a
        //   missing or duplicated bean would log nothing or twice.
        // Given/When: the module's auto-configuration alone, in a reactive web context
        contextRunner.run { context ->
            // Then: the filter bean (picked up by WebFlux via its Ordered contract) and the defaults exist
            assertThat(context).hasSingleBean(RequestLoggingWebFilter::class.java)
            assertThat(context).hasSingleBean(NanoTimeSource::class.java)
            assertThat(context).hasSingleBean(CorrelationIdGenerator::class.java)
            assertThat(context).hasSingleBean(HeaderValueMasker::class.java)
        }
    }

    @Test
    fun `should key the default masker from the masking-key property`() {
        // What is tested: the property path to a guess-proof fingerprint - no host bean needed.
        // Success criteria: with masking-key set, the masker bean renders the keyed fingerprint, not the
        //   unkeyed default.
        // Why it matters: keying is the documented answer to "masked is not a security boundary for
        //   guessable values"; it must be reachable from application.yml alone.
        // Given/When
        contextRunner.withPropertyValues("endpoint-logging.masking-key=k").run { context ->
            // Then
            assertThat(context.getBean(HeaderValueMasker::class.java).mask("secret-token")).isEqualTo("12:18da04f7cd594ea3")
        }
    }

    @Test
    fun `should back off entirely when disabled by the identical property`() {
        // What is tested: endpoint-logging.enabled=false on the reactive stack.
        // Success criteria: no filter, no defaults, no properties bean.
        // Why it matters: the master switch is one key for both twins; a bean surviving the switch
        //   would still register itself into the filter chain.
        // Given/When: the context with endpoint-logging.enabled=false
        contextRunner.withPropertyValues("endpoint-logging.enabled=false").run { context ->
            // Then: nothing of this module is in the context
            assertThat(context).doesNotHaveBean(RequestLoggingWebFilter::class.java)
            assertThat(context).doesNotHaveBean(NanoTimeSource::class.java)
            assertThat(context).doesNotHaveBean(HeaderValueMasker::class.java)
            assertThat(context).doesNotHaveBean(RequestLoggingProperties::class.java)
        }
    }

    @Test
    fun `should bind the identical properties namespace`() {
        // What is tested: four endpoint-logging.* keys bound into the reactive module's properties
        //   class.
        // Success criteria: logger name, wildcard include, masked list and the measuring flag carry
        //   the configured values.
        // Why it matters: the namespace is a cross-stack contract; a key that bound on the servlet
        //   twin but not here would break the twin symmetry silently.
        // Given/When: the identical endpoint-logging.* keys bound into this module's properties class
        contextRunner
            .withPropertyValues(
                "endpoint-logging.logger-name=reactive-exchange",
                "endpoint-logging.request-headers.includes=*",
                "endpoint-logging.request-headers.masked=Authorization",
                "endpoint-logging.measure-response-body-size=true",
            ).run { context ->
                // Then: the bound properties carry the configured values - same namespace, same shape
                val properties = context.getBean(RequestLoggingProperties::class.java)
                assertThat(properties.loggerName).isEqualTo("reactive-exchange")
                assertThat(properties.requestHeaders.includes).containsExactly("*")
                assertThat(properties.requestHeaders.masked).containsExactly("Authorization")
                assertThat(properties.measureResponseBodySize).isTrue()
            }
    }

    @Test
    fun `should register the MDC accessors when context-propagation is on the classpath`() {
        // What is tested: the initializer bean and its writes into the JVM-global ContextRegistry.
        // Success criteria: the bean exists and the registry carries one accessor per endpoint_*
        //   MDC key.
        // Why it matters: handler-side MDC for the Reactor variant rests on these accessors;
        //   without them the identity would vanish on the first thread hop.
        // Given: the JVM-global registry, restored by the class-level guard so the accessors do not leak
        //   into other tests
        // When: context-propagation on the test classpath, the Reactor variant active
        contextRunner.run { context ->
            // Then: the initializer bean ran (context-propagation IS on this test classpath) and the
            //   JVM-global registry carries one accessor per endpoint_* key
            assertThat(context).hasBean("endpointMdcContextPropagationInitializer")
            val keys = ContextRegistry.getInstance().threadLocalAccessors.map { it.key() }
            assertThat(keys).contains(MdcKeys.REQUEST_ID, MdcKeys.REQUEST_METHOD, MdcKeys.ROUTE)
        }
    }

    @Test
    fun `should select the coroutine variant when the coroutine libraries are present`() {
        // What is tested: the shipped two-auto-configuration system on the full classpath (both
        //   kotlinx-coroutines libraries present, as on this test classpath).
        // Success criteria: exactly ONE EndpointLoggingFilter, and it is the coroutine variant - the
        //   Reactor variant backed off via @ConditionalOnMissingBean.
        // Why it matters: a regression in condition names, ordering (before = ...), or the missing-bean
        //   back-off would register two filters (duplicate events) or the wrong variant - invisible to
        //   any test that loads one auto-configuration in isolation.
        // Given/When: both shipped auto-configurations on the full classpath
        shippedContextRunner.run { context ->
            // Then: exactly one filter, the coroutine variant
            assertThat(context).hasSingleBean(EndpointLoggingFilter::class.java)
            assertThat(context.getBean(EndpointLoggingFilter::class.java)).isInstanceOf(CoRequestLoggingWebFilter::class.java)
            assertThat(context).doesNotHaveBean(RequestLoggingWebFilter::class.java)
        }
    }

    @Test
    fun `should fall back to the reactor variant when the coroutine classpath is missing`() {
        // What is tested: the classpath fallback branch of the shipped system - the coroutine libraries
        //   are OPTIONAL dependencies, so most consumers run without them.
        // Success criteria: with CoWebFilter/MDCContext hidden from the classloader, exactly one filter
        //   registers and it is the Reactor variant.
        // Why it matters: this is the majority consumer configuration, and no test covered it - a broken
        //   @ConditionalOnClass would leave those applications with no filter or a startup failure.
        // Given/When: the coroutine classes hidden from the classloader
        shippedContextRunner
            .withClassLoader(FilteredClassLoader(CoWebFilter::class.java, MDCContext::class.java))
            .run { context ->
                // Then: exactly one filter, the Reactor variant
                assertThat(context).hasSingleBean(EndpointLoggingFilter::class.java)
                assertThat(context.getBean(EndpointLoggingFilter::class.java)).isInstanceOf(RequestLoggingWebFilter::class.java)
            }
    }

    @Test
    fun `should force the reactor variant by property although the coroutine libraries are present`() {
        // What is tested: the explicit override of the classpath-based selection - a Reactor-only host that
        //   pulls the coroutine libraries in
        //   transitively must be able to say so.
        // Success criteria: with endpoint-logging.variant=reactor on the FULL classpath, the single filter
        //   is the Reactor variant.
        // Why it matters: without the switch the variant was decided by transitive dependencies the host
        //   may not even know about.
        // Given/When: variant=reactor on the full classpath
        shippedContextRunner.withPropertyValues("endpoint-logging.variant=reactor").run { context ->
            // Then: the Reactor variant alone
            assertThat(context).hasSingleBean(EndpointLoggingFilter::class.java)
            assertThat(context.getBean(EndpointLoggingFilter::class.java)).isInstanceOf(RequestLoggingWebFilter::class.java)
        }
    }

    @Test
    fun `should fail the context start when the coroutine variant is demanded but its libraries are missing`() {
        // What is tested: variant=coroutine is a requirement, not a preference - silently serving the
        //   Reactor variant would hide a classpath mistake.
        // Success criteria: with the coroutine classes hidden, the context fails with a message naming the
        //   missing libraries.
        // Why it matters: a host demanding the coroutine variant relies on its native handler MDC; a
        //   silent Reactor fallback would surface the classpath mistake only as MDC-less production logs.
        // Given/When: variant=coroutine with the coroutine classes hidden
        shippedContextRunner
            .withClassLoader(FilteredClassLoader(CoWebFilter::class.java, MDCContext::class.java))
            .withPropertyValues("endpoint-logging.variant=coroutine")
            .run { context ->
                // Then: the context fails, naming the libraries
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause().hasMessageContaining("kotlinx-coroutines-reactor")
            }
    }

    @Test
    fun `should back off both variants behind a host-defined filter`() {
        // What is tested: the host-override contract of the SHIPPED system - a host bean of the
        //   EndpointLoggingFilter contract must silence both auto-configurations, not just the one the
        //   isolated test loads.
        // Success criteria: the host's filter is the single EndpointLoggingFilter; neither variant bean
        //   exists beside it.
        // Why it matters: a partial back-off would run the host's filter AND a module variant - every
        //   exchange logged twice.
        // Given/When: a host-defined filter bean beside the shipped pair
        shippedContextRunner.withUserConfiguration(HostConfig::class.java).run { context ->
            // Then: the host's bean alone
            assertThat(context).hasSingleBean(EndpointLoggingFilter::class.java)
            assertThat(context.getBean(EndpointLoggingFilter::class.java)).isSameAs(context.getBean("hostWebFilter"))
            assertThat(context).doesNotHaveBean(CoRequestLoggingWebFilter::class.java)
        }
    }

    @Test
    fun `should ship both auto-configurations through the imports resource`() {
        // What is tested: the actual registration file - the runner tests above wire the classes
        //   directly, so a missing or misspelled imports entry would stay invisible to them.
        // Success criteria: the merged AutoConfiguration.imports resources on the classpath contain both
        //   variant auto-configurations by fully qualified name.
        // Why it matters: the imports resource IS the module's activation in a consumer application;
        //   without the entry nothing else in this test class describes shipped behavior.
        // Given/When: the merged AutoConfiguration.imports resources on the classpath
        val lines =
            javaClass.classLoader
                .getResources("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                .toList()
                .flatMap { it.readText().lines() }
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        // Then: both variants are listed
        assertThat(lines).contains(
            CoRequestLoggingAutoConfiguration::class.java.name,
            RequestLoggingAutoConfiguration::class.java.name,
        )
    }

    @Test
    fun `should warn at startup when automatic context propagation is not configured`() {
        // What is tested: the validated prerequisite of the handler-MDC feature - Boot's default
        //   spring.reactor.context-propagation=limited does not
        //   restore MDC around ordinary operators, so registering the accessors without the `auto` mode
        //   must be called out at startup.
        // Success criteria: with the property unset the initializer emits one WARN naming the property;
        //   with `auto` configured it stays silent.
        // Why it matters: without the warning the feature fails silently - handler logs simply lack the
        //   correlation id, which is exactly the defect class the original KDoc promise concealed.
        val log = LoggerFactory.getLogger(EndpointMdcContextPropagation::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        log.addAppender(appender)
        try {
            // Given/When: the Reactor variant with the propagation mode unset
            contextRunner.run { context ->
                // Then: one WARN naming the property
                assertThat(context).hasBean("endpointMdcContextPropagationInitializer")
                assertThat(appender.list)
                    .anySatisfy {
                        assertThat(it.level).isEqualTo(Level.WARN)
                        assertThat(it.formattedMessage).contains("spring.reactor.context-propagation")
                    }
            }
            appender.list.clear()
            // When: the mode is auto
            contextRunner.withPropertyValues("spring.reactor.context-propagation=auto").run { context ->
                // Then: silent
                assertThat(context).hasBean("endpointMdcContextPropagationInitializer")
                assertThat(appender.list).isEmpty()
            }
        } finally {
            log.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `should not warn about context propagation when the coroutine variant owns the filter slot`() {
        // What is tested: the initializer's activation rule - the accessors and the propagation-mode warning
        //   belong to the REACTOR variant only.
        // Success criteria: with both auto-configurations shipped (coroutine variant selected) and the
        //   propagation mode unset, no WARN is logged by the propagation initializer.
        // Why it matters: the coroutine variant delivers handler MDC natively; a warning pushing hosts
        //   towards a global Reactor hook they do not need is false noise at every startup.
        val log = LoggerFactory.getLogger(EndpointMdcContextPropagation::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        log.addAppender(appender)
        try {
            // Given/When: the shipped pair with the coroutine variant selected and the mode unset
            shippedContextRunner.run { context ->
                // Then: the coroutine variant is active and nothing was warned
                assertThat(context.getBean(EndpointLoggingFilter::class.java)).isInstanceOf(CoRequestLoggingWebFilter::class.java)
                assertThat(appender.list).isEmpty()
            }
        } finally {
            log.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `should register the meters in a host-provided registry and let a host filter win`() {
        // What is tested: a host MeterRegistry and a host filter bean beside the auto-
        //   configuration.
        // Success criteria: the host's filter is the only one, and the fail-open counters are pre-
        //   registered in the host registry.
        // Why it matters: overriding the filter must keep the wiring, and the meters must land
        //   where the host exports them, not in a private registry.
        // Given/When: a host MeterRegistry and a host filter bean
        contextRunner.withUserConfiguration(HostConfig::class.java).run { context ->
            // Then: the host's filter bean is the only one, and the meters landed in the host registry
            assertThat(context).hasSingleBean(RequestLoggingWebFilter::class.java)
            assertThat(context.getBean(RequestLoggingWebFilter::class.java)).isSameAs(context.getBean("hostWebFilter"))
            val registry = context.getBean(MeterRegistry::class.java)
            assertThat(registry.find(EndpointLoggingMetrics.FAIL_OPEN_METER).counters()).hasSize(3)
        }
    }

    @Test
    fun `should start and register the accessors beside two host-defined filters`() {
        // What is tested: the propagation initializer with an AMBIGUOUS filter slot - two host-defined
        //   Reactor filters, a constellation the back-off contract permits.
        // Success criteria: the context starts, both host filters exist, the module's own filter backed
        //   off, and the accessors are registered because at least one Reactor variant owns a slot.
        // Why it matters: resolved through ObjectProvider.getIfAvailable() the initializer threw
        //   NoUniqueBeanDefinitionException and failed the context start from a logging library (code
        //   analysis of 2026-09-05, finding 4).
        // Given/When: two host filters beside the auto-configuration
        contextRunner.withUserConfiguration(TwoHostFiltersConfig::class.java).run { context ->
            // Then: started, both host filters present, accessors registered
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(EndpointLoggingFilter::class.java)).hasSize(2)
            assertThat(context).doesNotHaveBean("requestLoggingWebFilter")
            val keys = ContextRegistry.getInstance().threadLocalAccessors.map { it.key() }
            assertThat(keys).contains(MdcKeys.REQUEST_ID, MdcKeys.REQUEST_METHOD, MdcKeys.ROUTE)
        }
    }
}

// Host configuration at file level: a @Configuration class local to a test method holds a hidden
// reference to the enclosing test instance, which Spring cannot instantiate as a bean.

@Configuration(proxyBeanMethods = false)
private class HostConfig {
    @Bean
    fun hostMeterRegistry(): MeterRegistry = SimpleMeterRegistry()

    @Bean
    fun hostWebFilter(
        properties: RequestLoggingProperties,
        registry: MeterRegistry,
    ): RequestLoggingWebFilter = RequestLoggingWebFilter(properties, NanoTimeSource.SYSTEM, CorrelationIdGenerator.DEFAULT, registry)
}

@Configuration(proxyBeanMethods = false)
private class TwoHostFiltersConfig {
    @Bean
    fun firstHostWebFilter(properties: RequestLoggingProperties): RequestLoggingWebFilter = RequestLoggingWebFilter(properties, NanoTimeSource.SYSTEM, CorrelationIdGenerator.DEFAULT, SimpleMeterRegistry())

    @Bean
    fun secondHostWebFilter(properties: RequestLoggingProperties): RequestLoggingWebFilter = RequestLoggingWebFilter(properties, NanoTimeSource.SYSTEM, CorrelationIdGenerator.DEFAULT, SimpleMeterRegistry())
}

@Configuration(proxyBeanMethods = false)
private class MaskerHostConfig {
    @Bean
    fun hostMasker(): HeaderValueMasker = HeaderValueMasker { "***" }
}
