package com.kaoyan.wordhelper.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ServerHealthParseTest {
    @Test fun `empty object is not a cardback server`() {
        assertNull(ServerHealth.parse("{}"))
    }

    @Test fun `unrelated json is rejected`() {
        assertNull(ServerHealth.parse("""{"msg":"hello"}"""))
    }

    @Test fun `another service is rejected`() {
        assertNull(ServerHealth.parse("""{"service":"other","version":"1.0"}"""))
    }

    @Test fun `cardback identity is accepted`() {
        val info = ServerHealth.parse(
            """{"ok":true,"service":"cardback-editor","version":"1.4.7.2","protocol":3,"instance_id":"cb-abc"}"""
        )
        assertNotNull(info)
        assertEquals("cb-abc", info!!.instanceId)
        assertEquals("1.4.7.2", info.version)
    }

    @Test fun `old health without service is still accepted by version`() {
        val info = ServerHealth.parse("""{"ok":true,"version":"1.4.7.1"}""")
        assertNotNull(info)
        assertEquals("1.4.7.1", info!!.version)
    }

    @Test fun `explicit failure is rejected`() {
        assertNull(ServerHealth.parse("""{"ok":false,"version":"1.4.7.2"}"""))
    }
}
