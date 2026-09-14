package com.matiasnl.hakiosk.data.device.mqtt

/** QoS levels used by the app. QoS 2 is not needed for commands or state. */
enum class MqttQosLevel { AT_MOST_ONCE, AT_LEAST_ONCE }

/** A message received on a subscribed topic. */
class MqttMessage(val topic: String, val payload: ByteArray, val retained: Boolean) {
    val payloadText: String get() = payload.toString(Charsets.UTF_8)
}

/** Last Will: published by the broker if the connection drops without a clean DISCONNECT. */
data class MqttWill(val topic: String, val payload: String, val qos: MqttQosLevel, val retain: Boolean)

/** Everything needed to open one MQTT 3.1.1 connection. [toString] redacts [password]. */
data class MqttConnectParams(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val clientId: String,
    val username: String?,
    val password: String?,
    val keepAliveSeconds: Int,
    val connectTimeoutMillis: Long,
    val will: MqttWill?,
) {
    override fun toString(): String =
        "MqttConnectParams(host=$host, port=$port, useTls=$useTls, clientId=$clientId, username=$username, " +
            "password=${if (password == null) "null" else "***"}, keepAliveSeconds=$keepAliveSeconds, will=$will)"
}

/** The broker rejected the credentials (CONNACK "bad user name or password" or "not authorized"). */
class MqttAuthException(message: String) : Exception(message)

/**
 * Opens MQTT connections. Abstracted from the concrete library so the connection state machine can
 * be tested on the JVM with a fake.
 */
fun interface MqttClientFactory {
    /**
     * Connects and suspends until the CONNACK is accepted. [onMessage] receives every incoming
     * PUBLISH for the life of the session (called from a library thread; must not block).
     *
     * @throws MqttAuthException if the credentials were rejected.
     * @throws Exception for any other failure (network, TLS, timeout, protocol).
     */
    suspend fun connect(params: MqttConnectParams, onMessage: (MqttMessage) -> Unit): MqttSession
}

/** One established connection. Not reused after it closes: a new one is opened to reconnect. */
interface MqttSession {
    /** Suspends until the connection is lost or closed and returns the cause. */
    suspend fun awaitClosed(): Throwable

    suspend fun publish(topic: String, payload: ByteArray, qos: MqttQosLevel, retain: Boolean)

    suspend fun subscribe(topicFilter: String, qos: MqttQosLevel)

    suspend fun unsubscribe(topicFilter: String)

    /** Clean DISCONNECT: the broker discards the Last Will. */
    suspend fun disconnect()
}
