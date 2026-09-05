package eu.inqudium.limesium.reactive.logging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.HandlerMapping

/**
 * Pins the mirrored attribute name against the real WebFlux constant. The production code spells the
 * name itself to stay free of a `spring-webflux` dependency; the constant is computed from the class
 * name, so it cannot be inlined at compile time. This test - `spring-webflux` IS on the test classpath -
 * turns a silent rename into a build failure (twin parity with the servlet module).
 */
class HandlerMappingAttributeTest {
    @Test
    fun `should mirror the best-matching-pattern attribute name WebFlux actually uses`() {
        // What is tested: the mirrored attribute-name literal against WebFlux's own HandlerMapping
        //   constant.
        // Success criteria: the two strings are equal.
        // Why it matters: the filter reads the pattern by attribute name; a WebFlux rename would
        //   silently drop endpoint_url_template from every event without this pin.
        // Given/When/Then: the mirrored literal against the real WebFlux constant
        assertThat(RequestLoggingWebFilter.BEST_MATCHING_PATTERN_ATTRIBUTE).isEqualTo(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
    }
}
