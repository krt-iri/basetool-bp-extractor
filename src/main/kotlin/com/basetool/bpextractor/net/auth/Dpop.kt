package com.basetool.bpextractor.net.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.net.URI
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/**
 * A DPoP proof-of-possession key pair (RFC 9449) — EC **P-256 / ES256**, the one curve the basetool
 * ingest gateway is tested against (`REQ-INGEST-012`, `DpopResourceServerTest` in the basetool repo).
 *
 * <p>The point of binding is **token theft**, not impersonation: the "remember me" refresh token
 * lives on disk in Windows Credential Manager ([WinCredentialStore]), and sender-constraining makes
 * a copied token worthless to anyone who does not also hold this private key. The key therefore has
 * to outlive the process exactly as long as the refresh token does — see [encoded] and
 * [StoredCredential], which persist the two together as one record.
 *
 * <p>Pure JDK crypto on purpose: no JOSE library is pulled in, so the slim jpackage runtime is
 * unchanged. {@code SunEC} lives in {@code java.base} on JDK 25 (verified — no extra jlink module),
 * and {@code SHA256withECDSAinP1363Format} yields the raw 64-byte R‖S signature JWS ES256 wants
 * without any DER unwrapping.
 *
 * <p>**Never log an instance or [encoded].** The class deliberately keeps the JDK's identity
 * {@code toString}, so a stray interpolation cannot spill key material.
 */
class DpopKey private constructor(private val keyPair: KeyPair) {

    /**
     * The public key as the RFC 7638 *canonical* JWK: exactly the required members, lexicographically
     * ordered ({@code crv}, {@code kty}, {@code x}, {@code y}), no extras. Serving as both the proof
     * header's {@code jwk} and the thumbprint input keeps the two provably consistent — the server
     * recomputes {@code cnf.jkt} from what we send here.
     */
    val publicJwk: JsonObject = jwkOf(keyPair.public as ECPublicKey)

    /** The RFC 7638 JWK SHA-256 thumbprint — what Keycloak stamps into the token's `cnf.jkt`. */
    val thumbprint: String = base64Url(sha256(canonicalJson(publicJwk)))

    /**
     * Builds and signs one DPoP proof JWT for a single request (RFC 9449 §4.2).
     *
     * @param htm the HTTP method the proof is bound to, upper-case (e.g. `POST`)
     * @param htu the absolute request URI **without query and fragment** — build it with [htu]
     * @param accessToken the token the proof accompanies; adds the `ath` hash (required at a
     *   resource server, absent at the token endpoint where no token is presented yet)
     * @param issuedAt the `iat` instant — pass [ServerClock.now] so a drifting local clock does not
     *   put it outside the server's window
     * @param jti the proof's unique id — **fresh per proof**, defaulted to a random UUID; a reused
     *   one is exactly what a replay looks like to a server that caches them
     * @return the serialized `header.payload.signature` proof
     */
    fun proof(
        htm: String,
        htu: String,
        accessToken: String? = null,
        issuedAt: Instant = Instant.now(),
        jti: String = UUID.randomUUID().toString(),
    ): String {
        val header =
            buildJsonObject {
                put("typ", "dpop+jwt")
                put("alg", "ES256")
                put("jwk", publicJwk)
            }
        val claims =
            buildJsonObject {
                put("jti", jti)
                put("htm", htm)
                put("htu", htu)
                put("iat", issuedAt.epochSecond)
                // RFC 9449 §4.2: base64url of the SHA-256 over the ASCII access-token value.
                if (accessToken != null) put("ath", base64Url(sha256(accessToken.toByteArray(Charsets.US_ASCII))))
            }
        val signingInput = "${base64Url(canonicalJson(header))}.${base64Url(canonicalJson(claims))}"
        val signature =
            Signature.getInstance(ES256).run {
                initSign(keyPair.private)
                update(signingInput.toByteArray(Charsets.US_ASCII))
                sign()
            }
        return "$signingInput.${base64Url(signature)}"
    }

    /**
     * Serializes the key pair for [CredentialStore] persistence: the PKCS#8 private and X.509 public
     * encodings joined by `.` (neither alphabet contains a dot). Both halves are kept because the JDK
     * offers no way to re-derive an EC public key from a private one.
     *
     * @return the opaque encoded key pair — **secret**, never log or export it anywhere else
     */
    fun encoded(): String =
        "${Base64.getEncoder().encodeToString(keyPair.private.encoded)}." +
            Base64.getEncoder().encodeToString(keyPair.public.encoded)

    companion object {
        /** JWS `ES256` = ECDSA on P-256 with SHA-256; P1363 output is the raw R‖S JWS wants. */
        private const val ES256 = "SHA256withECDSAinP1363Format"

        /** NIST P-256 / `secp256r1` — the curve `REQ-INGEST-012` is tested against. */
        private const val CURVE = "secp256r1"

        private val COMPACT = Json

        /**
         * Generates a fresh P-256 key pair — one per "remember me" credential, or one per session
         * when nothing is persisted.
         *
         * @return the new key
         */
        fun generate(): DpopKey {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec(CURVE))
            return DpopKey(generator.generateKeyPair())
        }

        /**
         * Restores a key previously produced by [encoded]. **Fail-safe**, like [CredentialStore]
         * itself: anything unreadable (truncated blob, foreign curve, a record written by another
         * build) yields `null` so the caller simply starts over with a fresh key and an interactive
         * login, rather than the send flow dying on a corrupt vault entry.
         *
         * @param encoded the string [encoded] produced
         * @return the restored key, or `null` when it cannot be read back
         */
        fun fromEncoded(encoded: String): DpopKey? =
            try {
                val (privatePart, publicPart) = encoded.split('.', limit = 2).let { it[0] to it[1] }
                val factory = KeyFactory.getInstance("EC")
                val private =
                    factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privatePart)))
                val public =
                    factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicPart)))
                require(public is ECPublicKey && public.params.curve.field.fieldSize == 256)
                DpopKey(KeyPair(public, private))
            } catch (_: Exception) {
                null
            }

        /**
         * The `htu` value for a request URI: RFC 9449 §4.2 wants the target URI **without query and
         * fragment**. Also drops a redundant default port and lower-cases scheme + host (RFC 3986 §6
         * normalisation), because the server compares against the URI *it* reconstructs — an
         * explicit `:443` we sent but it did not would fail the proof for no good reason.
         *
         * @param uri the absolute request URI
         * @return the normalized absolute URI to put in the proof
         */
        fun htu(uri: URI): String {
            val scheme = uri.scheme.orEmpty().lowercase()
            val host = uri.host.orEmpty().lowercase()
            val defaultPort = if (scheme == "https") 443 else if (scheme == "http") 80 else -1
            val authority = if (uri.port == -1 || uri.port == defaultPort) host else "$host:${uri.port}"
            return "$scheme://$authority${uri.rawPath.orEmpty()}"
        }

        /** The canonical (RFC 7638) public JWK: required members only, in lexicographic order. */
        private fun jwkOf(key: ECPublicKey): JsonObject {
            val length = (key.params.curve.field.fieldSize + 7) / 8
            return buildJsonObject {
                put("crv", "P-256")
                put("kty", "EC")
                put("x", base64Url(coordinate(key.w.affineX, length)))
                put("y", base64Url(coordinate(key.w.affineY, length)))
            }
        }

        /**
         * An [ECPoint] coordinate as the fixed-width big-endian octet string JWK requires: strips
         * `BigInteger`'s sign byte and left-pads short values, which a raw `toByteArray()` would get
         * wrong for roughly one key in 256 in each direction.
         */
        private fun coordinate(value: BigInteger, length: Int): ByteArray {
            val raw = value.toByteArray()
            if (raw.size == length) return raw
            val out = ByteArray(length)
            if (raw.size > length) {
                raw.copyInto(out, 0, raw.size - length, raw.size)
            } else {
                raw.copyInto(out, length - raw.size, 0, raw.size)
            }
            return out
        }

        private fun canonicalJson(value: JsonObject): ByteArray =
            COMPACT.encodeToString(JsonObject.serializer(), value).toByteArray(Charsets.UTF_8)

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun base64Url(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * The clock one server's proofs are stamped from, corrected towards **that server's** time.
 *
 * <p>**Why this has to exist.** A proof's `iat` is written by the client but judged by the server,
 * inside a narrow window: Keycloak accepts −25s…+15s, the ingest gateway ±60s. A desktop clock that
 * runs ~15 seconds fast — invisible in the taskbar, and entirely harmless to the plain-bearer builds
 * that send no timestamp at all — therefore breaks *every* proof, and with it the whole login, since
 * Keycloak validates the proof before it even looks at the grant. Rather than leave that to the user
 * to diagnose, the offset is measured from the HTTP `Date` every response carries (RFC 9110 §6.6.1)
 * and added to `iat`.
 *
 * <p>This can only ever aim *at* the server's window, never widen it — the server judges by its own
 * clock regardless — so a wrong or even forged `Date` costs nothing but a rejected proof. Each server
 * gets its own instance for the same reason each gets its own [DpopNonce]: their clocks are unrelated.
 */
class ServerClock {

    @Volatile private var offset: Long = 0

    /**
     * How far the server's clock is ahead of this machine's, in seconds (negative ⇒ this machine
     * runs fast). Zero until a `Date` has been seen — and, on a healthy machine, ever after.
     */
    fun offsetSeconds(): Long = offset

    /** The instant to stamp into a proof's `iat`: local time, corrected towards the server. */
    fun now(): Instant = Instant.now().plusSeconds(offset)

    /**
     * Learns the offset from one response. Ignores an absent or unparseable header, so a server that
     * omits `Date` simply leaves the local clock in charge.
     *
     * @param dateHeader the response's `Date` value
     * @param sentAt local time immediately before the request went out; with local time *now* it
     *   brackets the moment the server stamped the header, and the midpoint removes half the
     *   round-trip bias (immaterial against a 15-second window, but free)
     */
    fun observe(dateHeader: String?, sentAt: Instant) {
        val server = parseHttpDate(dateHeader) ?: return
        val midpoint = sentAt.plusMillis(Duration.between(sentAt, Instant.now()).toMillis() / 2)
        offset = Duration.between(midpoint, server).seconds
    }

    companion object {
        /**
         * The offset change that justifies re-sending a rejected request with a corrected `iat`.
         * Below it the first proof was already inside every server's window, so the rejection had
         * another cause and a retry would only repeat it.
         */
        const val MATERIAL_SECONDS = 5L

        /**
         * The offset at which the clock is worth *telling the user about* — comfortably inside
         * Keycloak's ±15s tolerance, so it is only ever reported when it really is the likely cause.
         */
        const val REPORTABLE_SECONDS = 10L

        private fun parseHttpDate(value: String?): Instant? =
            try {
                if (value.isNullOrBlank()) {
                    null
                } else {
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                }
            } catch (_: Exception) {
                null
            }
    }
}

/**
 * RFC 9449 §8's **nonce challenge — deliberately not implemented.**
 *
 * <p>A server may refuse a proof and hand out a `DPoP-Nonce` it wants echoed back in a new one.
 * Neither server in this system does: Spring Security 7.1, which the ingest gateway runs, has no
 * resource-server nonce support at all, and the string `use_dpop_nonce` appears nowhere in Keycloak.
 * Carrying an untested handshake for a case that cannot currently arise buys no security and no
 * confidence — it only guarantees that the first real challenge would be answered by code nobody has
 * ever seen run.
 *
 * <p>So the challenge is **detected and reported by name** instead. If one ever arrives, the send
 * fails immediately with [CODE], the UI says plainly that the server wants a handshake this build
 * does not speak, and fixing it is a release — a loud, once-only event rather than a silent
 * dependency on untested code. Detection is the whole of the implementation.
 */
object DpopNonce {

    /** The response header carrying a nonce; its mere presence on a 4xx is the challenge. */
    const val HEADER = "DPoP-Nonce"

    /** The RFC 9449 §8 error code an authorization server names in the challenge. */
    const val USE_DPOP_NONCE = "use_dpop_nonce"

    /** The reason code the UI keys its explanation off (client-synthesized, not a server code). */
    const val CODE = "DPOP_NONCE_REQUIRED"

    /**
     * Reports a challenge on the one channel this app has. There is no logging framework here and
     * none may be added lightly — the tool must stay stateless on disk, so a log file next to the
     * exe is out — and the packaged app has no console, so this reaches a developer running from a
     * terminal and nobody else. **The user-facing half is the UI message keyed on [CODE]**; this is
     * only so the cause is not invisible while diagnosing. The nonce itself is server-issued and not
     * a secret, but it is not echoed here either — the endpoint is all anyone needs.
     *
     * @param endpoint the URL that issued the challenge
     */
    fun reportChallenge(endpoint: String) {
        System.err.println(
            "DPoP: $endpoint demanded a nonce (RFC 9449 §8). This build does not implement the " +
                "nonce handshake; the request was NOT retried. This needs a new release.",
        )
    }
}
