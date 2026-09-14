package com.matiasnl.hakiosk.data.ha

import com.matiasnl.hakiosk.data.ha.camera.HaCameraProtocol
import com.matiasnl.hakiosk.data.ha.camera.HaCameraStreamType
import com.matiasnl.hakiosk.data.ha.camera.HaIceCandidate
import com.matiasnl.hakiosk.data.ha.camera.HaIceServer
import com.matiasnl.hakiosk.data.ha.camera.HaWebRtcClientConfig
import com.matiasnl.hakiosk.data.ha.camera.HaWebRtcEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaCameraProtocolTest {
    private fun obj(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun streamTypesMapKnownValuesAndIgnoreUnknown() {
        assertEquals(
            setOf(HaCameraStreamType.WEB_RTC, HaCameraStreamType.HLS),
            HaCameraProtocol.parseStreamTypes(obj("""{"frontend_stream_types":["hls","web_rtc","mjpeg",3]}""")),
        )
        assertEquals(emptySet<HaCameraStreamType>(), HaCameraProtocol.parseStreamTypes(obj("""{"frontend_stream_types":[]}""")))
        assertNull(HaCameraProtocol.parseStreamTypes(obj("""{"other":1}""")))
        assertNull(HaCameraProtocol.parseStreamTypes(Json.parseToJsonElement("null")))
    }

    @Test
    fun clientConfigParsesIceServersDefensively() {
        val config = HaCameraProtocol.parseClientConfig(
            obj(
                """{"configuration":{"iceServers":[
                {"urls":"stun:stun.home-assistant.io:3478"},
                {"urls":["turn:turn.example:3478","turns:turn.example:5349"],"username":"u","credential":"c"},
                {"urls":[]},
                {"username":"no-urls"},
                "garbage"
                ]},"dataChannel":"x"}""",
            ),
        )
        assertEquals(
            HaWebRtcClientConfig(
                listOf(
                    HaIceServer(listOf("stun:stun.home-assistant.io:3478")),
                    HaIceServer(listOf("turn:turn.example:3478", "turns:turn.example:5349"), "u", "c"),
                ),
            ),
            config,
        )
        assertEquals(HaWebRtcClientConfig(emptyList()), HaCameraProtocol.parseClientConfig(obj("""{"configuration":{}}""")))
        assertNull(HaCameraProtocol.parseClientConfig(Json.parseToJsonElement("[]")))
    }

    @Test
    fun webRtcEventsMapToContractTypes() {
        assertEquals(HaWebRtcEvent.Session("01J"), HaCameraProtocol.parseWebRtcEvent(obj("""{"type":"session","session_id":"01J"}""")))
        assertEquals(HaWebRtcEvent.Answer("v=0"), HaCameraProtocol.parseWebRtcEvent(obj("""{"type":"answer","answer":"v=0"}""")))
        assertEquals(
            HaWebRtcEvent.RemoteCandidate(HaIceCandidate("candidate:1 1 udp 1 1.2.3.4 5 typ host", "0", 0)),
            HaCameraProtocol.parseWebRtcEvent(
                obj("""{"type":"candidate","candidate":{"candidate":"candidate:1 1 udp 1 1.2.3.4 5 typ host","sdpMid":"0","sdpMLineIndex":0}}"""),
            ),
        )
        assertEquals(
            HaWebRtcEvent.Error("webrtc_offer_failed", "boom"),
            HaCameraProtocol.parseWebRtcEvent(obj("""{"type":"error","code":"webrtc_offer_failed","message":"boom"}""")),
        )
        assertEquals(HaWebRtcEvent.Error("unknown_error", "WebRTC session failed"), HaCameraProtocol.parseWebRtcEvent(obj("""{"type":"error"}""")))
        assertNull(HaCameraProtocol.parseWebRtcEvent(obj("""{"type":"session"}""")))
        assertNull(HaCameraProtocol.parseWebRtcEvent(obj("""{"type":"something_new"}""")))
    }

    @Test
    fun candidatesAreParsedDefensively() {
        // HA 2024.11 sent the candidate as a bare string.
        assertEquals(HaIceCandidate("candidate:a", null, null), HaCameraProtocol.parseCandidate(Json.parseToJsonElement("\"candidate:a\"")))
        assertEquals(
            HaIceCandidate("candidate:b", null, null),
            HaCameraProtocol.parseCandidate(obj("""{"candidate":"candidate:b","sdpMLineIndex":-1,"sdpMid":null}""")),
        )
        assertEquals(HaIceCandidate("candidate:c", "video", 1), HaCameraProtocol.parseCandidate(obj("""{"candidate":"candidate:c","sdpMid":"video","sdpMLineIndex":"1"}""")))
        assertNull(HaCameraProtocol.parseCandidate(obj("""{"candidate":""}""")))
        assertNull(HaCameraProtocol.parseCandidate(obj("""{"sdpMid":"0"}""")))
        assertNull(HaCameraProtocol.parseCandidate(Json.parseToJsonElement("42")))
        assertNull(HaCameraProtocol.parseCandidate(null))
    }

    @Test
    fun candidateMessageUsesCamelCaseInitAndOmitsNulls() {
        assertEquals(
            obj(
                """{"id":7,"type":"camera/webrtc/candidate","entity_id":"camera.door","session_id":"s1",
                "candidate":{"candidate":"candidate:x","sdpMid":"0","sdpMLineIndex":0}}""",
            ),
            HaCameraProtocol.webRtcCandidate(7, "camera.door", "s1", HaIceCandidate("candidate:x", "0", 0)),
        )
        assertEquals(
            obj("""{"candidate":"candidate:y"}"""),
            HaCameraProtocol.webRtcCandidate(8, "camera.door", "s1", HaIceCandidate("candidate:y", null, null))["candidate"],
        )
        assertEquals(
            obj("""{"id":9,"type":"camera/webrtc/offer","entity_id":"camera.door","offer":"v=0"}"""),
            HaCameraProtocol.webRtcOffer(9, "camera.door", "v=0"),
        )
    }
}
