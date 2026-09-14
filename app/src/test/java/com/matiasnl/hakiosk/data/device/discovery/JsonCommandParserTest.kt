package com.matiasnl.hakiosk.data.device.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonCommandParserTest {

    @Test
    fun parsesOpenCameraWithCloseAfter() {
        val command = JsonCommandParser.parse("""{"command":"open_camera","entity_id":"camera.doorbell","close_after":30}""")

        assertEquals(JsonCommand.OpenCamera("camera.doorbell", 30), command)
    }

    @Test
    fun parsesOpenCameraWithoutCloseAfter() {
        val command = JsonCommandParser.parse("""{"command":"open_camera","entity_id":"camera.doorbell"}""")

        assertEquals(JsonCommand.OpenCamera("camera.doorbell", null), command)
    }

    @Test
    fun openCameraWithNonNumericCloseAfterFallsBackToNull() {
        val command = JsonCommandParser.parse("""{"command":"open_camera","entity_id":"camera.doorbell","close_after":"soon"}""")

        assertEquals(JsonCommand.OpenCamera("camera.doorbell", null), command)
    }

    @Test
    fun openCameraWithoutEntityIdIsIgnored() {
        assertNull(JsonCommandParser.parse("""{"command":"open_camera"}"""))
        assertNull(JsonCommandParser.parse("""{"command":"open_camera","entity_id":""}"""))
    }

    @Test
    fun parsesCloseCamera() {
        assertEquals(JsonCommand.CloseCamera, JsonCommandParser.parse("""{"command":"close_camera"}"""))
    }

    @Test
    fun parsesShowView() {
        assertEquals(JsonCommand.ShowView("Cocina"), JsonCommandParser.parse("""{"command":"show_view","view":"Cocina"}"""))
    }

    @Test
    fun showViewWithoutViewIsIgnored() {
        assertNull(JsonCommandParser.parse("""{"command":"show_view"}"""))
    }

    @Test
    fun parsesMainView() {
        assertEquals(JsonCommand.MainView, JsonCommandParser.parse("""{"command":"main_view"}"""))
    }

    @Test
    fun parsesScreen() {
        assertEquals(JsonCommand.Screen(true), JsonCommandParser.parse("""{"command":"screen","on":true}"""))
        assertEquals(JsonCommand.Screen(false), JsonCommandParser.parse("""{"command":"screen","on":false}"""))
    }

    @Test
    fun screenWithoutOnIsIgnored() {
        assertNull(JsonCommandParser.parse("""{"command":"screen"}"""))
        assertNull(JsonCommandParser.parse("""{"command":"screen","on":"yes"}"""))
    }

    @Test
    fun parsesBrightness() {
        assertEquals(JsonCommand.Brightness(40), JsonCommandParser.parse("""{"command":"brightness","value":40}"""))
    }

    @Test
    fun brightnessWithoutValueIsIgnored() {
        assertNull(JsonCommandParser.parse("""{"command":"brightness"}"""))
    }

    @Test
    fun parsesReload() {
        assertEquals(JsonCommand.Reload, JsonCommandParser.parse("""{"command":"reload"}"""))
    }

    @Test
    fun unknownCommandIsIgnored() {
        assertNull(JsonCommandParser.parse("""{"command":"nuke"}"""))
    }

    @Test
    fun malformedJsonIsIgnored() {
        assertNull(JsonCommandParser.parse("not json"))
        assertNull(JsonCommandParser.parse("""{"command":"""))
        assertNull(JsonCommandParser.parse("""["command","reload"]"""))
    }

    @Test
    fun commandOfTheWrongTypeIsIgnored() {
        assertNull(JsonCommandParser.parse("""{"command":123}"""))
        assertNull(JsonCommandParser.parse("""{"command":{"nested":true}}"""))
    }

    @Test
    fun closeAfterOfTheWrongTypeStillYieldsTheCommandWithoutIt() {
        assertEquals(
            JsonCommand.OpenCamera("camera.x", null),
            JsonCommandParser.parse("""{"command":"open_camera","entity_id":"camera.x","close_after":[1,2]}"""),
        )
    }
}
