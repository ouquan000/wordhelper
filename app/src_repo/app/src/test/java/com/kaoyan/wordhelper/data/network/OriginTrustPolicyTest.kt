package com.kaoyan.wordhelper.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginTrustPolicyTest {
    @Test
    fun `paired private http needs proof`() {
        assertTrue(OriginTrustPolicy.needsProof("http://192.168.1.8:8765", "cb-abc"))
        assertTrue(OriginTrustPolicy.needsProof("http://10.0.0.2:8765", "cb-abc"))
    }

    @Test
    fun `unpaired or https does not need proof`() {
        assertFalse(OriginTrustPolicy.needsProof("http://192.168.1.8:8765", ""))
        assertFalse(OriginTrustPolicy.needsProof("https://sync.ouquansdiary.cn", "cb-abc"))
    }

    @Test
    fun `only 2xx can be remembered as last good`() {
        assertTrue(OriginTrustPolicy.canRememberSuccess(200))
        assertTrue(OriginTrustPolicy.canRememberSuccess(204))
        assertFalse(OriginTrustPolicy.canRememberSuccess(401))
        assertFalse(OriginTrustPolicy.canRememberSuccess(404))
        assertFalse(OriginTrustPolicy.canRememberSuccess(500))
        assertFalse(OriginTrustPolicy.canRememberSuccess(null))
    }
}
