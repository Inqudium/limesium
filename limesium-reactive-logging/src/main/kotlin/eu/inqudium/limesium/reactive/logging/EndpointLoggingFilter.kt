package eu.inqudium.limesium.reactive.logging

import org.springframework.core.Ordered
import org.springframework.web.server.WebFilter

/**
 * Common contract of this module's two filter variants - the Reactor-based [RequestLoggingWebFilter] and
 * the coroutine-based [CoRequestLoggingWebFilter]. Exactly ONE of them is active per application: the
 * auto-configurations condition their beans on the ABSENCE of any [EndpointLoggingFilter], so the
 * variants back each other off, and a host-defined bean of either variant backs off both.
 */
interface EndpointLoggingFilter :
    WebFilter,
    Ordered
