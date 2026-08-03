package com.basetool.bpextractor.net.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets

/**
 * The persisted "remember me" credential: the refresh token **and** the DPoP key it is bound to
 * (RFC 9449, `REQ-INGEST-012`). The two travel as one record on purpose — a sender-constrained
 * refresh token cannot be redeemed without its private key, and a key without its token is dead
 * weight, so storing them apart would only create ways for them to drift out of step. One record
 * also means "Vom Basetool trennen" ([CredentialStore.clear]) still disposes of everything at once.
 *
 * @param refreshToken the Keycloak refresh token
 * @param dpopKey the [DpopKey.encoded] key pair the token is bound to, or `null` for a token
 *   obtained without DPoP (a record written by an older build, or a Keycloak that did not bind)
 */
@Serializable
data class StoredCredential(val refreshToken: String, val dpopKey: String? = null) {

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * Serializes the record for [CredentialStore.save].
         *
         * @param credential the record to encode
         * @return the opaque blob to hand to the vault — **secret**, never log it
         */
        fun encode(credential: StoredCredential): String =
            JSON.encodeToString(serializer(), credential)

        /**
         * Reads a blob back. A blob that is not a JSON object is a **legacy** entry — builds before
         * DPoP stored the bare refresh token — and is read as exactly that, with no key; the next
         * successful login rewrites it in the current shape. A corrupt JSON record yields `null`
         * (fail-safe, like the store itself), so the caller falls back to an interactive login
         * instead of redeeming garbage.
         *
         * @param blob whatever [CredentialStore.load] returned
         * @return the record, or `null` when the blob is unusable
         */
        fun decode(blob: String): StoredCredential? =
            if (!blob.startsWith("{")) {
                StoredCredential(blob)
            } else {
                try {
                    JSON.decodeFromString(serializer(), blob).takeIf { it.refreshToken.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
            }
    }
}

/**
 * The per-user OS secret store for the single "remember me" credential (epic
 * krt-profit/basetool#639, sub-issue #648). One secret, one fixed target — the abstraction is
 * parameterless so callers cannot smear secrets across keys. Implementations must be **fail-safe**:
 * a store that is unavailable (wrong OS, locked vault) returns "no credential" rather than throwing,
 * so the send flow simply falls back to a fresh device-grant login.
 *
 * <p>Implementations deal in opaque strings only; [StoredCredential] is layered on top via
 * [saveCredential]/[loadCredential] so the record shape can evolve without touching any vault
 * binding.
 */
interface CredentialStore {

    /**
     * Persists (overwriting) the refresh token.
     *
     * @param secret the refresh token to store
     * @return {@code true} on success, {@code false} if the store is unavailable or the write failed
     */
    fun save(secret: String): Boolean

    /**
     * Reads the stored refresh token.
     *
     * @return the token, or {@code null} when none is stored or the store is unavailable
     */
    fun load(): String?

    /**
     * Removes the stored refresh token (the "Vom Basetool trennen" action).
     *
     * @return {@code true} if nothing remains stored afterwards (already-absent counts as success)
     */
    fun clear(): Boolean

    /**
     * Whether a token is currently stored — drives the connected/disconnected UI state.
     *
     * @return {@code true} when {@link #load()} would return a non-null value
     */
    fun exists(): Boolean = load() != null

    /**
     * Persists the refresh token together with the DPoP key it is bound to.
     *
     * @param credential the record to store
     * @return {@code true} on success, {@code false} if the store is unavailable or the write failed
     */
    fun saveCredential(credential: StoredCredential): Boolean =
        save(StoredCredential.encode(credential))

    /**
     * Reads the stored credential, transparently upgrading a legacy bare-refresh-token entry.
     *
     * @return the record, or {@code null} when nothing usable is stored
     */
    fun loadCredential(): StoredCredential? = load()?.let { StoredCredential.decode(it) }
}

/**
 * Windows Credential Manager-backed [CredentialStore]: a single {@code CRED_TYPE_GENERIC} entry
 * under [target], protected per-user by the OS (DPAPI under the hood). Implemented with the JDK
 * Foreign Function &amp; Memory API (`java.lang.foreign`) against {@code Advapi32.dll}
 * ({@code CredWriteW} / {@code CredReadW} / {@code CredDeleteW} / {@code CredFree}) — no native
 * dependency, no JNI. The native binding is touched only on Windows; on any other OS every method
 * degrades to "no credential" so the desktop tool still runs (and the send flow falls back to a
 * fresh login). {@code --enable-native-access=ALL-UNNAMED} is already set for the app and tests.
 */
class WinCredentialStore(private val target: String = DEFAULT_TARGET) : CredentialStore {

    override fun save(secret: String): Boolean {
        if (!WINDOWS) return false
        return try {
            Arena.ofConfined().use { arena ->
                val blob = secret.toByteArray(StandardCharsets.UTF_8)
                val blobSeg = arena.allocate(blob.size.toLong().coerceAtLeast(1L))
                MemorySegment.copy(blob, 0, blobSeg, ValueLayout.JAVA_BYTE, 0L, blob.size)
                val cred = arena.allocate(CRED_SIZE, 8L) // zero-filled; pointer fields are aligned
                cred.set(ValueLayout.JAVA_INT, OFF_TYPE, CRED_TYPE_GENERIC)
                cred.set(ValueLayout.ADDRESS, OFF_TARGET_NAME, wide(arena, target))
                cred.set(ValueLayout.JAVA_INT, OFF_BLOB_SIZE, blob.size)
                cred.set(ValueLayout.ADDRESS, OFF_BLOB, blobSeg)
                cred.set(ValueLayout.JAVA_INT, OFF_PERSIST, CRED_PERSIST_LOCAL_MACHINE)
                cred.set(ValueLayout.ADDRESS, OFF_USER_NAME, wide(arena, target))
                (Win.credWrite.invokeExact(cred, 0) as Int) != 0
            }
        } catch (_: Throwable) {
            false
        }
    }

    override fun load(): String? {
        if (!WINDOWS) return null
        return try {
            Arena.ofConfined().use { arena ->
                val out = arena.allocate(ValueLayout.ADDRESS)
                val ok = Win.credRead.invokeExact(wide(arena, target), CRED_TYPE_GENERIC, 0, out) as Int
                if (ok == 0) return null
                val credPtr = out.get(ValueLayout.ADDRESS, 0L)
                try {
                    val cred = credPtr.reinterpret(CRED_SIZE)
                    val size = cred.get(ValueLayout.JAVA_INT, OFF_BLOB_SIZE)
                    if (size <= 0) return null
                    val blob = cred.get(ValueLayout.ADDRESS, OFF_BLOB).reinterpret(size.toLong())
                    String(blob.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8)
                } finally {
                    Win.credFree.invokeExact(credPtr)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    override fun clear(): Boolean {
        if (!WINDOWS) return true
        return try {
            Arena.ofConfined().use { arena ->
                val ok = Win.credDelete.invokeExact(wide(arena, target), CRED_TYPE_GENERIC, 0) as Int
                // ERROR_NOT_FOUND (already absent) is success for our purposes.
                ok != 0 || load() == null
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** Allocates a null-terminated UTF-16LE (Windows {@code LPCWSTR}) copy of [s] in [arena]. */
    private fun wide(arena: Arena, s: String): MemorySegment {
        val bytes = s.toByteArray(StandardCharsets.UTF_16LE)
        val seg = arena.allocate(bytes.size.toLong() + 2L, 2L) // + 2-byte NUL; rest is zero-filled
        MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.size)
        return seg
    }

    /**
     * The lazily-bound {@code Advapi32.dll} downcall handles. Initialized on first use, which only
     * happens on Windows (callers gate on [WINDOWS] first), so the library lookup never runs — and
     * never fails — on other operating systems.
     */
    private object Win {
        private val linker = Linker.nativeLinker()
        private val lookup = SymbolLookup.libraryLookup("Advapi32.dll", Arena.global())

        val credWrite =
            linker.downcallHandle(
                lookup.find("CredWriteW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            )
        val credRead =
            linker.downcallHandle(
                lookup.find("CredReadW").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                ),
            )
        val credDelete =
            linker.downcallHandle(
                lookup.find("CredDeleteW").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                ),
            )
        val credFree =
            linker.downcallHandle(
                lookup.find("CredFree").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
            )
    }

    companion object {
        /** The Credential Manager target name (one entry for the one basetool account). */
        const val DEFAULT_TARGET = "Basetool SC Extractor"

        /** {@code CRED_TYPE_GENERIC} — an app-private credential not tied to a network resource. */
        private const val CRED_TYPE_GENERIC = 1

        /** {@code CRED_PERSIST_LOCAL_MACHINE} — survives logoff, scoped to this machine + user. */
        private const val CRED_PERSIST_LOCAL_MACHINE = 2

        // CREDENTIALW field byte offsets on the Windows x64 ABI (natural alignment, 80-byte struct).
        private const val CRED_SIZE = 80L
        private const val OFF_TYPE = 4L
        private const val OFF_TARGET_NAME = 8L
        private const val OFF_BLOB_SIZE = 32L
        private const val OFF_BLOB = 40L
        private const val OFF_PERSIST = 48L
        private const val OFF_USER_NAME = 72L

        /** Whether the host OS is Windows (the only OS with a Credential Manager binding). */
        private val WINDOWS: Boolean =
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    }
}
