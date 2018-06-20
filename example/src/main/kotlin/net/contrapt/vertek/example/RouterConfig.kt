package net.contrapt.vertek.example

import io.vertx.core.Vertx
import net.contrapt.vertek.endpoints.ws.WebSocketConnector
import net.contrapt.vertek.endpoints.ws.WebSocketRouter
import net.contrapt.vertek.example.route.WsConsumer
import net.contrapt.vertek.example.router.MainRouter
import net.contrapt.vertek.example.router.RestRouter
import org.springframework.context.ApplicationContext
import org.springframework.context.support.beans

object RouterConfig {

    fun startup(vertx: Vertx, context: ApplicationContext) {
        val wsRouter = WebSocketRouter(vertx, "/ws/V1", setOf())
        val restRouter = RestRouter(vertx, "/api/V1")
        vertx.deployVerticle(context.getBean(WsConsumer::class.java))
        val router = MainRouter(listOf(wsRouter.router(), restRouter.router()))
        vertx.deployVerticle(router)
    }

    fun context() = beans {

        bean {
            val connector = WebSocketConnector("example.ws1.whatever")
            WsConsumer(connector)
        }
    }
}