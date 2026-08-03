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
import java.time.Instant
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
     * @param nonce the server-supplied `DPoP-Nonce` to echo, or `null` when none was demanded
     * @param issuedAt the `iat` instant (injected so tests can pin it)
     * @param jti the proof's unique id — **fresh per proof**, defaulted to a random UUID; a reused
     *   one is exactly what a replay looks like to a server that caches them
     * @return the serialized `header.payload.signature` proof
     */
    fun proof(
        htm: String,
        htu: String,
        accessToken: String? = null,
        nonce: String? = null,
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
                if (nonce != null) put("nonce", nonce)
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
 * One endpoint's DPoP nonce (RFC 9449 §8/§9). A server may refuse a proof and hand out a
 * `DPoP-Nonce` it wants echoed back; the nonces of the **authorization server** and of the
 * **resource server** are unrelated, so each client keeps its own instance and they must never be
 * shared. Mutated from whichever thread runs the send, hence `@Volatile`.
 */
class DpopNonce {

    @Volatile private var value: String? = null

    /** The nonce to put in the next proof, or `null` when the server has not demanded one. */
    fun current(): String? = value

    /**
     * Records a `DPoP-Nonce` a response carried. Blank/absent values are ignored so a response
     * without the header never clears a nonce the server still expects.
     *
     * @param nonce the response header value, or `null` when the response carried none
     */
    fun remember(nonce: String?) {
        if (!nonce.isNullOrBlank()) value = nonce
    }

    companion object {
        /** The response (and request-challenge) header carrying the nonce. */
        const val HEADER = "DPoP-Nonce"

        /** The OAuth2 / RFC 9449 error code that asks the client to retry with a nonce. */
        const val USE_DPOP_NONCE = "use_dpop_nonce"
    }
}
