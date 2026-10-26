package com.voiceclean.app.diag

import org.junit.Assert.*
import org.junit.Test

class DeviceProfileTest {
    @Test fun snapdragon8EliteGen5IsFlagship() {
        val p = DeviceProfile.from("QTI", "SM8850", "qcom", 16000, 8)
        assertEquals(SocVendor.QUALCOMM, p.vendor)
        assertEquals(DeviceTier.FLAGSHIP, p.tier)
        assertEquals("stt-large-v3-turbo-q5_0", p.recommendedStt.first())
    }
    @Test fun midRangeGetsSmallModels() {
        val p = DeviceProfile.from("QTI", "SM7435", "qcom", 8000, 4)
        assertTrue(p.recommendedStt.none { it.startsWith("stt-large") })
    }
    @Test fun lowRamDropsBigModels() {
        val p = DeviceProfile.from("Samsung", "Exynos 2400", "exynos", 3000, 4)
        assertTrue(p.recommendedStt.none { it.startsWith("stt-large") || it.startsWith("stt-medium") })
    }
    @Test fun catalogIdsExist() {
        listOf("SM8850", "SM7550", "SM6115").forEach {
            DeviceProfile.from("QTI", it, "qcom", 8000, 4).recommendedStt.forEach { id ->
                assertNotNull(id, com.voiceclean.app.models.ModelCatalog.byId(id))
            }
        }
    }
}
