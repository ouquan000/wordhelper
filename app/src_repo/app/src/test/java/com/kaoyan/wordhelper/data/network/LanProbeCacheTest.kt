package com.kaoyan.wordhelper.data.network

import org.junit.Assert.*
import org.junit.Test

class LanProbeCacheTest {
    @Test fun timeoutFallsBackThenAllowsAnotherLanAttempt() {
        val cache = LanProbeCache<String>()
        cache.record("wifi", null, 100)
        assertNotNull(cache.lookup("wifi", 200))
        assertNull(cache.lookup("wifi", 200)?.value)
        assertNull(cache.lookup("wifi", 2100))
        cache.record("wifi", "verified", 2200)
        assertEquals("verified", cache.lookup("wifi", 3000)?.value)
        assertNull(cache.lookup("wifi", 32200))
    }

    @Test fun switchingNetworksImmediatelyInvalidatesCachedOutcome() {
        val cache = LanProbeCache<String>()
        cache.record("mobile", null, 0)
        assertNull(cache.lookup("wifi", 1))
        cache.record("wifi", "verified", 2)
        assertNull(cache.lookup("other wifi", 3))
    }
}
