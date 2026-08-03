package com.basetool.bpextractor.net.auth

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the [CredentialStore] contract via the in-memory fake, plus a Windows-only round-trip
 * through the real [WinCredentialStore] that exercises the {@code Advapi32.dll} FFM marshalling
 * end-to-end (write → read → delete). The real round-trip uses a unique throwaway target and
 * deletes it afterwards, so it never touches the production "Basetool SC Extractor" entry and
 * leaves the developer's Credential Manager clean. No real basetool credentials are involved.
 */
class CredentialStoreTest {

    @Test
    fun `fake store round-trips, overwrites and clears`() {
        val store = FakeCredentialStore()
        assertNull(store.load())
        assertFalse(store.exists())

        assertTrue(store.save("token-1"))
        assertEquals("token-1", store.load())
        assertTrue(store.exists())

        store.save("token-2")
        assertEquals("token-2", store.load())

        assertTrue(store.clear())
        assertNull(store.load())
        assertFalse(store.exists())
    }

    @Test
    fun `a credential round-trips the refresh token together with its DPoP key`() {
        // The two must stay one record: a sender-constrained refresh token is unredeemable without
        // the key it was issued to (REQ-INGEST-012), so they can never be allowed to drift apart.
        val store = FakeCredentialStore()
        val key = DpopKey.generate()

        store.saveCredential(StoredCredential("RT-1", key.encoded()))
        val loaded = assertNotNull(store.loadCredential())

        assertEquals("RT-1", loaded.refreshToken)
        assertEquals(
            key.thumbprint,
            assertNotNull(DpopKey.fromEncoded(assertNotNull(loaded.dpopKey))).thumbprint,
            "the key that comes back has to be the same key",
        )
    }

    @Test
    fun `a legacy bare refresh token is still readable and simply carries no key`() {
        // What every build before DPoP wrote into the vault. It must keep working — the refresh then
        // mints a bound token against a fresh key, and the next save rewrites the entry.
        val store = FakeCredentialStore("eyJhbGciOiJIUzI1NiJ9.legacy-refresh-token")

        val loaded = assertNotNull(store.loadCredential())

        assertEquals("eyJhbGciOiJIUzI1NiJ9.legacy-refresh-token", loaded.refreshToken)
        assertNull(loaded.dpopKey)
    }

    @Test
    fun `a corrupt record reads as absent rather than as a garbage token`() {
        // Fail-safe: the caller falls back to an interactive login instead of redeeming nonsense.
        assertNull(FakeCredentialStore("""{"refreshToken":""}""").loadCredential())
        assertNull(FakeCredentialStore("""{"refreshToken":"RT-1",""").loadCredential())
        assertNull(FakeCredentialStore("""{}""").loadCredential())
    }

    @Test
    fun `windows credential manager round-trips a unicode secret`() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            return // FFM Advapi32 binding only exists on Windows; skip elsewhere.
        }
        val target = "Basetool SC Extractor TEST ${UUID.randomUUID()}"
        val store = WinCredentialStore(target)
        try {
            assertNull(store.load(), "a fresh target must start empty")

            // A non-ASCII secret proves the UTF-8 blob + UTF-16 target marshalling is correct.
            val secret = "refresh-Öß-${UUID.randomUUID()}"
            assertTrue(store.save(secret), "CredWriteW should succeed")
            assertEquals(secret, store.load(), "CredReadW should return the exact bytes written")
            assertTrue(store.exists())

            assertTrue(store.save("rotated"), "overwrite should succeed")
            assertEquals("rotated", store.load())
        } finally {
            store.clear()
            assertNull(store.load(), "the test entry must be gone after clear")
        }
    }
}
