package com.exodidio.tune.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchChromeHostTest {
    @Test
    fun showAndDismissOnlyAffectMatchingOwner() {
        val host = BatchChromeHostState()
        val firstOwner = Any()
        val secondOwner = Any()

        assertFalse(host.isActive)
        assertNull(host.request)

        host.show(id = firstOwner, topContent = {}, bottomContent = {})
        assertTrue(host.isActive)
        assertEquals(firstOwner, host.request?.id)

        // Dismissing with a different owner token must not clear active batch chrome.
        host.dismiss(secondOwner)
        assertTrue(host.isActive)
        assertEquals(firstOwner, host.request?.id)

        // Switching to a new owner replaces the request cleanly.
        host.show(id = secondOwner, topContent = {}, bottomContent = {})
        assertTrue(host.isActive)
        assertEquals(secondOwner, host.request?.id)

        // Dismissing with matching owner clears chrome; dismissAll always clears.
        host.dismiss(secondOwner)
        assertFalse(host.isActive)
        assertNull(host.request)

        host.show(id = firstOwner)
        assertTrue(host.isActive)
        host.dismissAll()
        assertFalse(host.isActive)
        assertNull(host.request)
    }
}
