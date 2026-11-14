package com.u1.slicer.data

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

/** Uses the original export API so these regressions also run unchanged on upstream. */
class BackupCredentialRegressionTest {
    private fun exported(): JSONObject {
        val printer = Printer(
            id = "printer-1", nickname = "Test printer", kind = PrinterKind.BAMBU_LAN,
            bambu = BambuConfig("192.0.2.1", "test-access-code", "test-serial", BambuModel.P1S),
        )
        return JSONObject(SettingsBackup.export(SettingsBackup.BackupData(
            sliceConfig = null, slicingOverrides = null, filamentProfiles = null,
            printersConfig = PrintersConfig(listOf(printer), printer.id),
            makerWorldCookies = "session=test-cookie",
        )))
    }

    @Test fun `default export excludes account cookies`() {
        assertFalse(exported().has("makerWorldCookies"))
    }

    @Test fun `default export excludes printer access code`() {
        assertEquals("", exported().getJSONArray("printers").getJSONObject(0)
            .getJSONObject("bambu").optString("accessCode"))
    }
}
