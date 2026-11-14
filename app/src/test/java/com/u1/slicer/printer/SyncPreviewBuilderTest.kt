package com.u1.slicer.printer

import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentLibrary
import com.u1.slicer.data.FilamentLibraryEntry
import com.u1.slicer.data.LibrarySnapshotInfo
import com.u1.slicer.network.FilamentSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPreviewBuilderTest {

    private val presets = (0..3).map { ExtruderPreset(index = it, color = "#111111", materialType = "PLA") }
    private val lib = FilamentLibrary(
        entries = listOf(
            FilamentLibraryEntry("prusament-pla-galaxy-black", "Prusament", "PLA Prusa Galaxy Black", "PLA", hex = "#3E413F"),
        ),
        snapshot = LibrarySnapshotInfo("test", "2026-06-10", 1),
    )

    private fun slot(i: Int, vendor: String, type: String = "PLA", hex: String = "#3E413F") =
        FilamentSlot(index = i, label = "E${i + 1}", color = hex, loaded = true,
            materialType = type, subType = "", manufacturer = vendor)

    @Test
    fun `matched slot carries catalogue name colour material`() {
        val entries = buildSyncPreviewEntries(presets, listOf(slot(0, "Prusament")), lib)
        val e = entries[0]
        assertEquals("Prusament PLA Prusa Galaxy Black", e.matchedName)
        assertEquals("prusament-pla-galaxy-black", e.matchedSlug)
        assertEquals("#3E413F", e.newColor)
        assertEquals("PLA", e.newType)
    }

    @Test
    fun `unmatched slot falls back to raw values exactly as before`() {
        val entries = buildSyncPreviewEntries(presets, listOf(slot(0, "Snapmaker", hex = "#FF0000")), lib)
        val e = entries[0]
        assertNull(e.matchedName)
        assertNull(e.matchedSlug)
        assertEquals("#FF0000", e.newColor)
        assertEquals("PLA", e.newType)
    }

    @Test
    fun `null library means no matching - raw behaviour`() {
        val entries = buildSyncPreviewEntries(presets, listOf(slot(0, "Prusament")), library = null)
        assertNull(entries[0].matchedName)
        assertEquals("#3E413F", entries[0].newColor)
    }

    @Test
    fun `missing printer slot yields null news`() {
        val entries = buildSyncPreviewEntries(presets, emptyList(), lib)
        assertEquals(4, entries.size)
        assertNull(entries[2].newColor)
        assertNull(entries[2].newType)
        assertNull(entries[2].matchedName)
    }

    @Test
    fun `four entries always built with current preset values`() {
        val entries = buildSyncPreviewEntries(presets, listOf(slot(1, "Prusament")), lib)
        assertEquals(listOf("E1", "E2", "E3", "E4"), entries.map { it.label })
        assertEquals("#111111", entries[0].currentColor)
        assertEquals("Prusament PLA Prusa Galaxy Black", entries[1].matchedName)
    }

    @Test
    fun `bambu empty tray keeps its AMS label and is not library matched`() {
        val emptyAmsSlot = FilamentSlot(
            index = 0,
            label = "AMS 1",
            color = "#808080",
            loaded = false,
            materialType = "Empty",
            manufacturer = "Prusament",
        )

        val entry = buildSyncPreviewEntries(presets, listOf(emptyAmsSlot), lib).first()

        assertEquals("AMS 1", entry.label)
        assertEquals("Empty", entry.newType)
        assertEquals("#808080", entry.newColor)
        assertNull(entry.matchedName)
        assertNull(entry.matchedSlug)
    }

    @Test
    fun `U1 empty slot does not overwrite preset during sync`() {
        val currentPresets = presets.map {
            if (it.index == 0) it.copy(filamentProfileId = 42L) else it
        }
        val emptySlot = FilamentSlot(
            index = 0,
            label = "E1",
            color = "#808080",
            loaded = false,
            materialType = "NONE",
            subType = "NONE",
            manufacturer = "Snapmaker",
        )

        val entry = buildSyncPreviewEntries(
            presets = currentPresets,
            slots = listOf(emptySlot),
            library = lib,
            skipEmptySlots = true,
        ).first()
        val applied = applySyncPreviewEntries(
            presets = currentPresets,
            entries = listOf(entry),
            applyColors = true,
            applyTypes = true,
        )

        assertEquals("E1", entry.label)
        assertEquals("#111111", entry.currentColor)
        assertEquals("PLA", entry.currentType)
        assertNull(entry.newColor)
        assertNull(entry.newType)
        assertEquals(currentPresets, applied)
    }

    @Test
    fun `NONE subtype is ignored when matching loaded U1 slot`() {
        val loadedSlot = slot(0, "Prusament").copy(subType = "NONE")

        val entry = buildSyncPreviewEntries(
            presets = presets,
            slots = listOf(loadedSlot),
            library = lib,
            skipEmptySlots = true,
        ).first()

        assertEquals("prusament-pla-galaxy-black", entry.matchedSlug)
        assertEquals("Prusament PLA Prusa Galaxy Black", entry.matchedName)
    }

    @Test
    fun `Bambu preview includes all sparse live routes and matches presets by id`() {
        val sparsePresets = listOf(
            ExtruderPreset(index = 128, color = "#AABBCC", materialType = "PA-CF"),
            ExtruderPreset(index = 0, color = "#010101", materialType = "PLA"),
        )
        val liveSlots = listOf(
            slot(0, "Bambu Lab"),
            FilamentSlot(4, "AMS 2 Tray 1", "#222222", true, "PETG"),
            FilamentSlot(128, "AMS-HT 1", "#333333", true, "PA-CF"),
            FilamentSlot(254, "External spool", "#444444", true, "TPU"),
        )

        val entries = buildSyncPreviewEntries(
            presets = sparsePresets,
            slots = liveSlots,
            library = null,
            includeAllPrinterSlots = true,
        )

        assertEquals(listOf(0, 4, 128, 254), entries.map { it.slotIndex })
        assertEquals(listOf("E1", "AMS 2 Tray 1", "AMS-HT 1", "External spool"), entries.map { it.label })
        assertEquals("#AABBCC", entries.first { it.slotIndex == 128 }.currentColor)
        assertEquals("PA-CF", entries.first { it.slotIndex == 128 }.currentType)
    }

    @Test
    fun `applying Bambu sync adds missing sparse route presets`() {
        val entries = buildSyncPreviewEntries(
            presets = emptyList(),
            slots = listOf(
                FilamentSlot(4, "AMS 2 Tray 1", "#222222", true, "PETG"),
                FilamentSlot(254, "External spool", "#444444", true, "TPU"),
            ),
            library = null,
            includeAllPrinterSlots = true,
        )

        val applied = applySyncPreviewEntries(
            presets = emptyList(),
            entries = entries,
            applyColors = true,
            applyTypes = true,
        )

        assertEquals(listOf(4, 254), applied.map { it.index })
        assertEquals(listOf("PETG", "TPU"), applied.map { it.materialType })
        assertEquals(listOf("AMS 2 Tray 1", "External spool"), applied.map { it.label })
    }
}
