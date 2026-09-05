package eu.inqudium.limesium.reactive.logging

import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory
import org.springframework.boot.web.server.reactive.ReactiveWebServerFactory
import org.springframework.context.annotation.Import

/**
 * [ServerContract] on Reactor Netty, the WebFlux starter's default: the native `HttpHandler` server,
 * body buffers backed by Netty `ByteBuf`s, signals on event-loop threads. Declared explicitly so the
 * choice does not depend on which server module Boot happens to detect first on a test classpath that
 * carries all three.
 */
@Import(NettyServer::class)
class ReactorNettyServerIntegrationTest : ServerContract() {
    override val server: Class<out ReactiveWebServerFactory> = NettyReactiveWebServerFactory::class.java
}
