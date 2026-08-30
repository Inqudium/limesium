package eu.inqudium.limesium.servlet.logging

import io.undertow.Undertow
import io.undertow.servlet.Servlets
import io.undertow.servlet.api.DeploymentManager
import jakarta.servlet.ServletContainerInitializer
import jakarta.servlet.ServletContext
import org.springframework.boot.web.server.WebServer
import org.springframework.boot.web.server.servlet.ServletWebServerFactory
import org.springframework.boot.web.servlet.ServletContextInitializer
import java.net.InetSocketAddress

/**
 * A MINIMAL hand-rolled Undertow [ServletWebServerFactory] for the Undertow capture-boundary suite:
 * Spring Boot 4 dropped its Undertow integration (Undertow has no Jakarta Servlet 6.1 release), so
 * running the module on the engine WildFly embeds requires bringing the factory ourselves. Deliberately
 * bare - one deployment at the root context, Boot's [ServletContextInitializer]s executed through a
 * real [ServletContainerInitializer] (the same bridge Boot's removed factory used), an ephemeral port.
 * Test infrastructure, not a supported production path - see the container-support note in the README.
 */
internal class UndertowTestServer : ServletWebServerFactory {
    override fun getWebServer(vararg initializers: ServletContextInitializer): WebServer {
        val deployment =
            Servlets
                .deployment()
                .setClassLoader(UndertowTestServer::class.java.classLoader)
                .setContextPath("/")
                .setDeploymentName("undertow-capture-boundary-test")
                .setEagerFilterInit(true)
                .addServletContainerInitializer(
                    io.undertow.servlet.api.ServletContainerInitializerInfo(
                        BootInitializerBridge::class.java,
                        io.undertow.servlet.util
                            .ImmediateInstanceFactory(BootInitializerBridge(initializers.toList())),
                        emptySet(),
                    ),
                )
        val manager: DeploymentManager = Servlets.defaultContainer().addDeployment(deployment)
        manager.deploy()
        val handler = manager.start()
        val undertow =
            Undertow
                .builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(handler)
                .build()
        return object : WebServer {
            override fun start() = undertow.start()

            override fun stop() {
                undertow.stop()
                manager.stop()
                manager.undeploy()
            }

            override fun getPort(): Int = (undertow.listenerInfo.single().address as InetSocketAddress).port
        }
    }

    /** Runs Boot's [ServletContextInitializer]s inside Undertow's SCI phase - Boot's removed factory used the same bridge. */
    internal class BootInitializerBridge(
        private val initializers: List<ServletContextInitializer>,
    ) : ServletContainerInitializer {
        override fun onStartup(
            classes: Set<Class<*>>?,
            servletContext: ServletContext,
        ) {
            initializers.forEach { it.onStartup(servletContext) }
        }
    }
}
