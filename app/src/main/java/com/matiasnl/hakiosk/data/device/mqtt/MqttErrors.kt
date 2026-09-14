package com.matiasnl.hakiosk.data.device.mqtt

import com.matiasnl.hakiosk.data.ha.HaNetworkErrors
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/** Human-readable description of an MQTT connection failure. Never includes credentials. */
internal object MqttErrors {
    fun describe(error: Throwable): String {
        if (error is TimeoutCancellationException || error is TimeoutException) return "Timed out"
        // Libraries wrap socket/TLS failures; describe the most specific network cause when there is one.
        var current: Throwable? = error
        while (current != null) {
            if (current is IOException || current is SSLException) return HaNetworkErrors.describe(current)
            current = current.cause.takeIf { it !== current }
        }
        return error.message ?: error.javaClass.simpleName
    }
}
