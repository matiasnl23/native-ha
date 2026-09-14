package com.matiasnl.hakiosk.data.device.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode
import com.hivemq.client.mqtt.mqtt3.message.subscribe.suback.Mqtt3SubAckReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.future.await
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit

/**
 * [MqttClientFactory] over the HiveMQ MQTT client (MQTT 3.1.1, async API). One client per session and
 * no library-level automatic reconnect: [MqttConnectionManager] owns reconnection. HiveMQ shares its
 * Netty event loop between clients and releases it when none is left.
 */
class HiveMqClientFactory : MqttClientFactory {

    override suspend fun connect(params: MqttConnectParams, onMessage: (MqttMessage) -> Unit): MqttSession {
        val closed = CompletableDeferred<Throwable>()
        var builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier(params.clientId)
            .serverHost(params.host)
            .serverPort(params.port)
            .transportConfig()
            .socketConnectTimeout(params.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .mqttConnectTimeout(params.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .applyTransportConfig()
            .addDisconnectedListener { context -> closed.complete(context.cause) }
        // Default TLS config: platform trust store (system and user CAs, per the network security
        // config) with hostname verification.
        if (params.useTls) builder = builder.sslWithDefaultConfig()
        val client = builder.buildAsync()

        client.publishes(MqttGlobalPublishFilter.ALL) { publish ->
            onMessage(MqttMessage(publish.topic.toString(), publish.payloadAsBytes, publish.isRetain))
        }

        var connect = client.connectWith()
            .keepAlive(params.keepAliveSeconds)
            .cleanSession(true)
        params.username?.let { username ->
            val auth = connect.simpleAuth().username(username)
            connect = (params.password?.let { auth.password(it.toByteArray(Charsets.UTF_8)) } ?: auth).applySimpleAuth()
        }
        params.will?.let { will ->
            connect = connect.willPublish()
                .topic(will.topic)
                .payload(will.payload.toByteArray(Charsets.UTF_8))
                .qos(will.qos.toHiveMq())
                .retain(will.retain)
                .applyWillPublish()
        }

        val connAck = connect.send()
        try {
            connAck.await()
        } catch (e: CancellationException) {
            // Don't leak a connection that completes after the caller gave up.
            connAck.whenComplete { _, error -> if (error == null) client.disconnect() }
            throw e
        } catch (e: Throwable) {
            val cause = (e as? CompletionException)?.cause ?: e
            if (cause is Mqtt3ConnAckException) {
                when (cause.mqttMessage.returnCode) {
                    Mqtt3ConnAckReturnCode.BAD_USER_NAME_OR_PASSWORD -> throw MqttAuthException("Bad user name or password")
                    Mqtt3ConnAckReturnCode.NOT_AUTHORIZED -> throw MqttAuthException("Not authorized")
                    else -> throw IllegalStateException("Connection refused: ${cause.mqttMessage.returnCode}", cause)
                }
            }
            throw cause
        }
        return HiveMqSession(client, closed)
    }

    private class HiveMqSession(
        private val client: Mqtt3AsyncClient,
        private val closed: CompletableDeferred<Throwable>,
    ) : MqttSession {
        override suspend fun awaitClosed(): Throwable = closed.await()

        override suspend fun publish(topic: String, payload: ByteArray, qos: MqttQosLevel, retain: Boolean) {
            client.publishWith().topic(topic).payload(payload).qos(qos.toHiveMq()).retain(retain).send().await()
        }

        override suspend fun subscribe(topicFilter: String, qos: MqttQosLevel) {
            val ack = client.subscribeWith().topicFilter(topicFilter).qos(qos.toHiveMq()).send().await()
            if (ack.returnCodes.any { it == Mqtt3SubAckReturnCode.FAILURE }) {
                throw IllegalStateException("Subscription to $topicFilter rejected")
            }
        }

        override suspend fun unsubscribe(topicFilter: String) {
            client.unsubscribeWith().topicFilter(topicFilter).send().await()
        }

        override suspend fun disconnect() {
            if (client.state.isConnectedOrReconnect) client.disconnect().await()
        }
    }

    private companion object {
        fun MqttQosLevel.toHiveMq(): MqttQos = when (this) {
            MqttQosLevel.AT_MOST_ONCE -> MqttQos.AT_MOST_ONCE
            MqttQosLevel.AT_LEAST_ONCE -> MqttQos.AT_LEAST_ONCE
        }
    }
}
