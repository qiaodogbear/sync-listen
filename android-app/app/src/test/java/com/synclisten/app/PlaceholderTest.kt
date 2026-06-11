package com.synclisten.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceholderTest {
    @Test
    fun projectPackageIsStable() {
        assertEquals("com.synclisten.app", BuildConfig.APPLICATION_ID.removeSuffix(".debug"))
    }
}

