package eu.inqudium.limesium.reactive.logging

import org.springframework.boot.jetty.reactive.JettyReactiveWebServerFactory
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory
import org.springframework.boot.tomcat.reactive.TomcatReactiveWebServerFactory
import org.springframework.boot.web.server.reactive.ReactiveWebServerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/*
 * One explicit ReactiveWebServerFactory bean per reactive server Boot 4 ships. The test classpath
 * carries all three servers, and Boot then auto-configures whichever module it detects FIRST (Jetty, as
 * the build log showed) - so every integration test names its server through one of these imports, and
 * Boot's own server auto-configuration backs off behind the bean. The ServerContract suites run one
 * each; the Netty-only integration tests import [NettyServer] to stay on the starter's default.
 */

/** Reactor Netty, the WebFlux starter's default: the native HttpHandler server. */
@Configuration(proxyBeanMethods = false)
class NettyServer {
    @Bean
    fun reactorNettyServer(): ReactiveWebServerFactory = NettyReactiveWebServerFactory()
}

/** Embedded Tomcat behind Spring's TomcatHttpHandlerAdapter - WebFlux over a servlet async cycle. */
@Configuration(proxyBeanMethods = false)
class TomcatServer {
    @Bean
    fun tomcatServer(): ReactiveWebServerFactory = TomcatReactiveWebServerFactory()
}

/** Embedded Jetty behind Spring's JettyHttpHandlerAdapter - WebFlux over Jetty's servlet async cycle. */
@Configuration(proxyBeanMethods = false)
class JettyServer {
    @Bean
    fun jettyServer(): ReactiveWebServerFactory = JettyReactiveWebServerFactory()
}
