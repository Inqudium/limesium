package eu.inqudium.limesium.common

import org.slf4j.MDC
import org.slf4j.spi.MDCAdapter

// Shared by the twins through limesium-common's test-jar (architecture review of 2026-09-05,
// finding 3 - the per-module copies the ADR-0003 amendment allowed grew to three).

/**
 * Installs [adapter] as the JVM-global SLF4J MDC adapter for fault injection. SLF4J exposes no public
 * setter, so the package-private `MDC.setMDCAdapter` is invoked reflectively; callers restore the
 * original adapter (`MDC.getMDCAdapter()` taken beforehand) in their teardown.
 */
fun installMdcAdapter(adapter: MDCAdapter) {
    MDC::class.java
        .getDeclaredMethod("setMDCAdapter", MDCAdapter::class.java)
        .apply { isAccessible = true }
        .invoke(null, adapter)
}
