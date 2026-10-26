package com.voiceclean.app.diag

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuInfoTest {
    @Test fun snapdragon8Gen2Layout() {
        // 1 prime, 4 gold, 3 little
        val f = listOf(2016000L, 2016000, 2016000, 2803200, 2803200, 2803200, 2803200, 3360000)
        assertEquals(5, CpuInfo.performanceCores(f, 8))
    }
    @Test fun fallbackWhenUnreadable() {
        assertEquals(4, CpuInfo.performanceCores(emptyList(), 8))
    }
}
