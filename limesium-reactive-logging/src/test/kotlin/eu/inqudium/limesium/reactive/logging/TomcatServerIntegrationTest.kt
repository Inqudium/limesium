package eu.inqudium.limesium.reactive.logging

import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.tomcat.reactive.TomcatReactiveWebServerFactory
import org.springframework.boot.web.server.reactive.ReactiveWebServerFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import

/**
 * [ServerContract] on embedded Tomcat behind Spring's `TomcatHttpHandlerAdapter`: WebFlux over a servlet
 * async cycle, body buffers copied out of the servlet streams, signals on container worker threads. The
 * explicit factory bean makes Boot's own server auto-configuration back off.
 */
@Import(TomcatServer::class)
class TomcatServerIntegrationTest(
    @LocalServerPort port: Int,
    context: ApplicationContext,
) : ServerContract(port, context) {
    override val server: Class<out ReactiveWebServerFactory> = TomcatReactiveWebServerFactory::class.java
}
