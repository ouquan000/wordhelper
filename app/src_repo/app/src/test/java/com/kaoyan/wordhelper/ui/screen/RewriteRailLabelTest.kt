package com.kaoyan.wordhelper.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class RewriteRailLabelTest {
    @Test fun flashTakesPriorityOverProAndOtherFamilies() {
        assertEquals("DS 4.1F", shortNameOf("GLM DeepSeek PRO Flash"))
    }

    @Test fun proRequiresTheDeepSeekFamily() {
        assertEquals("DS 4 Pro", shortNameOf("deepseek V4 PRO"))
        assertEquals("Falcon", shortNameOf("Falcon Pro"))
    }

    @Test fun matchesMixedCaseProviderNames() {
        assertEquals("GLM 5.3", shortNameOf("CN:GlM-5.3"))
        assertEquals("MiniMax", shortNameOf("cn:MINIMAX-v2"))
    }

    @Test fun unknownModelsUseTheirFirstSpaceDelimitedToken() {
        assertEquals("alpha-v2", shortNameOf("alpha-v2 preview"))
        assertEquals("alpha-v2", shortNameOf("alpha-v2"))
    }

    @Test fun emptyFirstTokenPreservesOriginalLabel() {
        for (name in listOf("", "   ", " unknown model")) {
            assertEquals(name, shortNameOf(name))
        }
    }
}
