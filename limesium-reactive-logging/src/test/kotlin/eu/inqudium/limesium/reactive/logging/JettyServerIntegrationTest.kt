package eu.inqudium.limesium.reactive.logging

import org.springframework.boot.jetty.reactive.JettyReactiveWebServerFactory
import org.springframework.boot.web.server.reactive.ReactiveWebServerFactory
import org.springframework.context.annotation.Import

/**
 * [ServerContract] on embedded Jetty behind Spring's `JettyHttpHandlerAdapter`: WebFlux over Jetty's
 * servlet async cycle, body buffers from Jetty's own pool, signals on Jetty's selector and worker
 * threads. The explicit factory bean makes Boot's own server auto-configuration back off.
 */
@Import(JettyServer::class)
class JettyServerIntegrationTest : ServerContract() {
    override val server: Class<out ReactiveWebServerFactory> = JettyReactiveWebServerFactory::class.java
}
