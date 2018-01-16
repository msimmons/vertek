package net.contrapt.vertx.endpoints

import com.rabbitmq.client.ConnectionFactory
import io.vertx.core.AbstractVerticle
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.groovy.rabbitmq.RabbitMQClient_GroovyExtension.basicAck
import io.vertx.groovy.rabbitmq.RabbitMQClient_GroovyExtension.basicNack
import net.contrapt.vertx.plugs.MessagePlug
import net.contrapt.vertx.plugs.Plug

/**
 * Represents the configuration of a message bus endpoint that we would like to
 * subscribe to
 */
abstract class RabbitConsumer(
    val connectionFactory: ConnectionFactory,
    val exchange: String,
    val routingKey: String,
    val queue: String,
    val durable: Boolean = true,
    val exclusive: Boolean = false,
    val autoDelete: Boolean = false,
    val autoAck: Boolean = true,
    val prefetchLimit: Int = 0,
    val consumers: Int = 1
) : AbstractVerticle(), Handler<Message<JsonObject>> {

    lateinit var client : RabbitClient

    protected val plugs = mutableListOf<MessagePlug>()

    val logger = LoggerFactory.getLogger(javaClass)

    override fun start() {
        client = RabbitClient.create(vertx, connectionFactory)
        client.start(startupHandler(client))
    }

    fun startupHandler(client: RabbitClient)= Handler<AsyncResult<Unit>> { async->
        when (async.succeeded()) {
            true -> {
                logger.info("Rabbit client connected")
                addPlugs()
                client.queueDeclare(queue, durable, exclusive, autoDelete, bindQueue())
                startInternal()
            }
            false -> {
                logger.warn("Unable to connect to rabbit", async.cause())
                logger.warn("Trying again in 10s")
                vertx.setTimer(10000, {
                    logger.info("Trying again")
                    start()
                })
            }
        }
    }

    /**
     * Handle the incoming [message] by first applying [Plug]s then executing this classes [handleMessage].  Any
     * unhandled exceptions will result in message being nacked if [autoAck] is not set
     */
    final override fun handle(message: Message<JsonObject>) {
        // TODO What if you want a transaction around message handling?
        // TODO How would you implement aggregation, and maybe other EIPs?
        vertx.executeBlocking(Handler<Future<Nothing>> { future ->
            try{
                processInbound(message)
                handleMessage(message)
                future.complete()
            }
            catch (e: Exception) {
                // TODO Exception handler?
                future.fail(e)
            }
        }, false, Handler<AsyncResult<Nothing>> {ar ->
            if ( ar.failed() ) basicNack(message, ar.cause())
            else basicAck(message)
        })
    }

    private fun processInbound(message: Message<JsonObject>) {
        plugs.forEach {
            it.process(message)
        }
    }

    /**
     * Override this method to start any additional consumers, timers etc when this consumer starts
     */
    abstract fun startInternal()

    /**
     * Override this method to implement the main [Message] handling code for this consumer
     */
    abstract fun handleMessage(message: Message<JsonObject>)

    /**
     * Override this method to add [Plug]s to this consumer.  They will be applied to the incoming [Message] in order
     * before the message is sent to [handleMessage]
     */
    open fun addPlugs() {}

    /**
     * Send [basicAck] if this consumer is not set to [autoAck]
     */
    private fun basicAck(message: Message<JsonObject>) {
        when (autoAck) {
            false -> {
                val consumerTag = message.body().getString("consumerTag")
                client.basicAck(consumerTag, message.body().getLong("deliveryTag"), false, Handler<AsyncResult<Unit>> {ar ->
                    if ( ar.failed() ) logger.error(ar.cause())
                })
            }
        }
    }

    /**
     * Send [basicNack] if this consumer is not set to [autoAck]
     */
    private fun basicNack(message: Message<JsonObject>, exception: Throwable) {
        when (autoAck) {
            false -> {
                val consumerTag = message.body().getString("consumerTag")
                client.basicNack(consumerTag, message.body().getLong("deliveryTag"), false, true, Handler<AsyncResult<Unit>> {ar ->
                    if ( ar.failed() ) logger.error(ar.cause())
                })
                logger.error(exception)
            }
        }
    }

    /**
     * Bind this consumer's [queue] to it's [exchange] and [routingKey]
     */
    private fun bindQueue() = Handler<AsyncResult<JsonObject>> { async ->
        if( async.failed() ) throw IllegalStateException("Failed to declare queue $queue", async.cause())
        client.queueBind(queue, exchange, routingKey, setupConsumer())
    }

    /**
     * Setup this consumer's [basicConsume]rs on rabbit as well as the [EventBus] consumer that will handle incoming
     * messages
     */
    private fun setupConsumer() = Handler<AsyncResult<Unit>> { async ->
        if ( async.failed() ) throw IllegalStateException("Failed to bind queue $exchange:$routingKey -> $queue", async.cause())
        // Basic consumes bridges rabbit message to the event bus
        (1..consumers).forEach {
            client.basicConsume(queue, queue, autoAck, prefetchLimit, Handler<AsyncResult<String>> { ar -> handleConsumer(ar)})
        }
        // Event bus consumer will pick up the bridged message -- the handler is this concrete subclass
        vertx.eventBus().consumer(queue, this)
    }

    private fun handleConsumer(ar: AsyncResult<String>) {
        when ( ar.succeeded() ) {
            true -> logger.info("Listening to $exchange:$routingKey -> $queue [${ar.result()}]")
            else -> logger.error("Failed to setup consumer $exchange:$routingKey -> $queue", ar.cause())
        }
    }

}
