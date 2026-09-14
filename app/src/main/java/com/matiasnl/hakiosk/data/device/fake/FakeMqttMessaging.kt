package com.matiasnl.hakiosk.data.device.fake

import com.matiasnl.hakiosk.data.device.MqttConnectionState
import com.matiasnl.hakiosk.data.device.mqtt.MqttMessage
import com.matiasnl.hakiosk.data.device.mqtt.MqttMessaging
import com.matiasnl.hakiosk.data.device.mqtt.MqttQosLevel
import com.matiasnl.hakiosk.data.device.mqtt.MqttTopics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [MqttMessaging] for tests: records every publish, delivers [emit] to matching
 * subscribers (like the real broker would), and lets tests drive [connectionState] directly.
 */
class FakeMqttMessaging(deviceId: String = "0123456789abcdef") : MqttMessaging {
    private val topics = MqttTopics(deviceId)

    private val _connectionState = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disabled)
    override val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    data class Published(val topic: String, val payload: String, val retain: Boolean)

    val published = mutableListOf<Published>()

    /** Whether [publish] succeeds; mirrors the real client returning false while disconnected. */
    private var isConnectedForPublish: Boolean = false

    private val subscriptions = mutableListOf<Pair<String, MutableSharedFlow<MqttMessage>>>()

    override suspend fun topics(): MqttTopics = topics

    override suspend fun publish(topic: String, payload: String, retain: Boolean, qos: MqttQosLevel): Boolean {
        if (!isConnectedForPublish) return false
        published += Published(topic, payload, retain)
        return true
    }

    override fun subscribe(topicFilter: String, qos: MqttQosLevel): Flow<MqttMessage> {
        val flow = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 16)
        subscriptions += topicFilter to flow
        return flow
    }

    /** Marks the fake connected/disconnected and updates [connectionState] accordingly. */
    fun setConnected(value: Boolean) {
        isConnectedForPublish = value
        _connectionState.value = if (value) {
            MqttConnectionState.Connected
        } else {
            MqttConnectionState.Disconnected("test", retryInMillis = 0)
        }
    }

    /** Delivers a message to every subscription whose filter matches [topic], as the broker would. */
    fun emit(topic: String, payload: String) {
        val message = MqttMessage(topic, payload.toByteArray(Charsets.UTF_8), retained = false)
        subscriptions.filter { (filter, _) -> MqttTopics.matches(filter, topic) }
            .forEach { (_, flow) -> check(flow.tryEmit(message)) { "Subscriber buffer full for $topic" } }
    }

    fun lastPublished(topic: String): String? = published.lastOrNull { it.topic == topic }?.payload
}
