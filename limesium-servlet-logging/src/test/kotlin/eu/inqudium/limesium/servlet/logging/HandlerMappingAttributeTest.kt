package eu.inqudium.limesium.servlet.logging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.HandlerMapping

/**
 * Pins the mirrored attribute name against the real Spring MVC constant. The production code spells the
 * name itself to stay free of a `spring-webmvc` dependency (the module must work in a non-MVC servlet
 * application); the constant is computed from the class name, so it cannot be inlined at compile time.
 * This test - `spring-webmvc` IS on the test classpath - turns a silent rename into a build failure.
 */
class HandlerMappingAttributeTest {
    @Test
    fun `should mirror the best-matching-pattern attribute name Spring MVC actually uses`() {
        // Given/When/Then: the mirrored literal against the real Spring MVC constant
        assertThat(RequestLoggingFilter.BEST_MATCHING_PATTERN_ATTRIBUTE).isEqualTo(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
    }
}
