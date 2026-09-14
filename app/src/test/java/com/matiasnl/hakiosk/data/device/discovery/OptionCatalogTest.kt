package com.matiasnl.hakiosk.data.device.discovery

import com.matiasnl.hakiosk.data.device.CameraOption
import com.matiasnl.hakiosk.data.device.ViewOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionCatalogTest {

    @Test
    fun uniqueNamesAreKeptAsIs() {
        val catalog = OptionCatalog.of(
            listOf(ViewOption("a", "Cocina"), ViewOption("b", "Living")),
            ViewOption::id,
            ViewOption::name,
        )

        assertEquals(listOf("Cocina", "Living"), catalog.options)
        assertEquals("Cocina", catalog.nameFor("a"))
        assertEquals(OptionCatalog.Resolution.Found("a"), catalog.resolve("Cocina"))
    }

    @Test
    fun duplicateNamesAreDisambiguatedInSourceOrder() {
        val views = listOf(
            ViewOption("a", "Cocina"),
            ViewOption("b", "Cocina"),
            ViewOption("c", "Living"),
            ViewOption("d", "Cocina"),
        )
        val catalog = OptionCatalog.of(views, ViewOption::id, ViewOption::name)

        assertEquals(listOf("Cocina", "Cocina (2)", "Living", "Cocina (3)"), catalog.options)
        assertEquals("Cocina", catalog.nameFor("a"))
        assertEquals("Cocina (2)", catalog.nameFor("b"))
        assertEquals("Cocina (3)", catalog.nameFor("d"))
        assertEquals(OptionCatalog.Resolution.Found("b"), catalog.resolve("Cocina (2)"))
    }

    @Test
    fun rebuildingFromTheSameListIsStable() {
        val views = listOf(ViewOption("a", "X"), ViewOption("b", "X"))
        val first = OptionCatalog.of(views, ViewOption::id, ViewOption::name)
        val second = OptionCatalog.of(views, ViewOption::id, ViewOption::name)

        assertEquals(first.options, second.options)
        assertEquals(first.resolve("X (2)"), second.resolve("X (2)"))
    }

    @Test
    fun resolvingAnUnknownNameIsUnknown() {
        val catalog = OptionCatalog.of(listOf(ViewOption("a", "Cocina")), ViewOption::id, ViewOption::name)

        assertEquals(OptionCatalog.Resolution.Unknown, catalog.resolve("Living"))
    }

    @Test
    fun nameForAMissingOrNullIdIsNull() {
        val catalog = OptionCatalog.of(listOf(ViewOption("a", "Cocina")), ViewOption::id, ViewOption::name)

        assertNull(catalog.nameFor("missing"))
        assertNull(catalog.nameFor(null))
    }

    @Test
    fun hasIdReflectsMembership() {
        val catalog = OptionCatalog.of(listOf(CameraOption("camera.front", "Frente")), CameraOption::entityId, CameraOption::name)

        assertTrue(catalog.hasId("camera.front"))
        assertFalse(catalog.hasId("camera.back"))
    }

    @Test
    fun emptyCatalogHasNoOptions() {
        assertEquals(emptyList<String>(), OptionCatalog.EMPTY.options)
        assertEquals(OptionCatalog.Resolution.Unknown, OptionCatalog.EMPTY.resolve("anything"))
    }
}
