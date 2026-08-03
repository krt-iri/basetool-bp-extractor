package com.basetool.bpextractor.net.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.net.URI
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the DPoP proof construction (RFC 9449) the ingest gateway validates
 * (`REQ-INGEST-012`). The reference is the basetool's own `DpopResourceServerTest`, which builds the
 * same proof server-side with Nimbus and asserts what Spring Security accepts; this suite checks the
 * hand-rolled JDK-crypto producer against that shape **from the outside** — every assertion is made
 * by decoding the emitted compact JWT and re-deriving the answer independently, never by asking the
 * producer what it thinks it wrote.
 *
 * <p>The decisive one is [proofSignatureVerifiesUnderTheEmbeddedJwk]: it rebuilds the public key
 * from the `jwk` header alone and verifies the signature over the actual signing input — exactly the
 * chain a resource server walks, so a wrong signature format, a mis-encoded coordinate or a
 * header/payload the signature does not cover all fail here.
 */
class DpopTest {

    private val key = DpopKey.generate()

    // --- helpers: decode the compact JWT the way a server would --------------------------------

    private fun decode(part: String): ByteArray = Base64.getUrlDecoder().decode(part)

    private fun jsonPart(part: String): JsonObject =
        Json.parseToJsonElement(decode(part).decodeToString()).jsonObject

    private fun header(proof: String): JsonObject = jsonPart(proof.split('.')[0])

    private fun claims(proof: String): JsonObject = jsonPart(proof.split('.')[1])

    private fun claim(proof: String, name: String): String? =
        claims(proof)[name]?.jsonPrimitive?.content

    /** Rebuilds the public key from the proof's own `jwk` header — no access to [key]'s internals. */
    private fun publicKeyFromHeader(proof: String): java.security.PublicKey {
        val jwk = header(proof)["jwk"]!!.jsonObject
        val params =
            AlgorithmParameters.getInstance("EC")
                .apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
        val point =
            ECPoint(
                BigInteger(1, decode(jwk["x"]!!.jsonPrimitive.content)),
                BigInteger(1, decode(jwk["y"]!!.jsonPrimitive.content)),
            )
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    // --- the proof itself -----------------------------------------------------------------------

    @Test
    fun proofSignatureVerifiesUnderTheEmbeddedJwk() {
        val proof = key.proof("POST", "https://ingest.example/v1/refinery-extract")
        val (headerPart, claimsPart, signaturePart) = proof.split('.')

        val verifier =
            Signature.getInstance("SHA256withECDSAinP1363Format").apply {
                initVerify(publicKeyFromHeader(proof))
                update("$headerPart.$claimsPart".toByteArray(Charsets.US_ASCII))
            }

        assertTrue(
            verifier.verify(decode(signaturePart)),
            "the ES256 signature must cover the exact header.payload the server re-derives",
        )
    }

    @Test
    fun aProofIsRejectedOnceAnyByteOfItIsTampered() {
        // Guards the assertion above against passing vacuously: verification must actually depend on
        // the signed bytes, so flipping a claim has to break it.
        val proof = key.proof("POST", "https://ingest.example/v1/refinery-extract")
        val (headerPart, claimsPart, signaturePart) = proof.split('.')
        val forged = base64Url(claims(proof).toString().replace("POST", "GET_").toByteArray())

        val verifier =
            Signature.getInstance("SHA256withECDSAinP1363Format").apply {
                initVerify(publicKeyFromHeader(proof))
                update("$headerPart.$forged".toByteArray(Charsets.US_ASCII))
            }

        assertFalse(verifier.verify(decode(signaturePart)), "a tampered payload must not verify")
        assertEquals(3, proof.split('.').size)
        assertEquals(64, decode(signaturePart).size, "ES256 is raw R‖S, not a DER blob")
    }

    @Test
    fun headerCarriesTheDpopTypeAlgorithmAndPublicJwk() {
        val header = header(key.proof("POST", "https://ingest.example/v1/refinery-extract"))

        assertEquals("dpop+jwt", header["typ"]!!.jsonPrimitive.content)
        assertEquals("ES256", header["alg"]!!.jsonPrimitive.content)
        val jwk = header["jwk"]!!.jsonObject
        assertEquals("EC", jwk["kty"]!!.jsonPrimitive.content)
        assertEquals("P-256", jwk["crv"]!!.jsonPrimitive.content)
        // Public members only — a private `d` here would ship the secret in every request.
        assertEquals(setOf("crv", "kty", "x", "y"), jwk.keys)
    }

    @Test
    fun jwkCoordinatesAreAlwaysTheFullFieldWidth() {
        // A raw BigInteger.toByteArray() is 33 bytes when the high bit is set and short when the
        // value has leading zero bytes — either way the thumbprint (and the whole binding) breaks.
        // ~1 key in 128 hits one of those, so a handful of keys makes it a real check.
        repeat(64) {
            val jwk = header(DpopKey.generate().proof("POST", "https://x/y"))["jwk"]!!.jsonObject
            assertEquals(32, decode(jwk["x"]!!.jsonPrimitive.content).size, "x must be 32 bytes")
            assertEquals(32, decode(jwk["y"]!!.jsonPrimitive.content).size, "y must be 32 bytes")
        }
    }

    @Test
    fun thumbprintIsTheRfc7638HashOfTheCanonicalJwk() {
        val proof = key.proof("POST", "https://ingest.example/v1/refinery-extract")
        val jwk = header(proof)["jwk"]!!.jsonObject
        // RFC 7638: required members only, lexicographic order, no whitespace. Rebuilt by hand so a
        // silent reordering in the producer cannot hide behind the producer's own serialization.
        val canonical =
            """{"crv":"P-256","kty":"EC","x":"${jwk["x"]!!.jsonPrimitive.content}",""" +
                """"y":"${jwk["y"]!!.jsonPrimitive.content}"}"""

        assertEquals(base64Url(sha256(canonical.toByteArray())), key.thumbprint)
    }

    @Test
    fun athIsTheBase64UrlSha256OfTheAccessToken() {
        val token = "eyJhbGciOiJSUzI1NiJ9.some-access-token"
        val proof = key.proof("POST", "https://ingest.example/v1/refinery-extract", accessToken = token)

        assertEquals(base64Url(sha256(token.toByteArray(Charsets.US_ASCII))), claim(proof, "ath"))
    }

    @Test
    fun athIsAbsentWhenNoTokenIsPresentedYet() {
        // The token endpoint gets a proof before any token exists; an `ath` there would hash nothing.
        val proof = key.proof("POST", "https://keycloak.example/realms/iri/protocol/openid-connect/token")

        assertNull(claims(proof)["ath"], "no access token ⇒ no ath claim")
        assertNull(claims(proof)["nonce"], "no nonce demanded ⇒ no nonce claim")
    }

    @Test
    fun boundMethodUriAndIssueTimeAreCarriedVerbatim() {
        val issued = Instant.ofEpochSecond(1_800_000_000L)
        val proof =
            key.proof("POST", "https://ingest.example/v1/refinery-extract", issuedAt = issued)

        assertEquals("POST", claim(proof, "htm"))
        assertEquals("https://ingest.example/v1/refinery-extract", claim(proof, "htu"))
        assertEquals("1800000000", claim(proof, "iat"))
    }

    @Test
    fun everyProofGetsAFreshJti() {
        // A repeated jti is indistinguishable from a replay to a server that caches them.
        val ids = (1..200).map { claim(key.proof("POST", "https://ingest.example/v1/x"), "jti") }

        assertEquals(200, ids.toSet().size, "each proof needs its own jti")
        assertTrue(ids.all { !it.isNullOrBlank() })
    }

    // --- htu normalisation ----------------------------------------------------------------------

    @Test
    fun htuDropsQueryAndFragment() {
        // RFC 9449 §4.2. The gateway compares against the URI it reconstructs, which carries neither.
        assertEquals(
            "https://ingest.example/v1/refinery-extract",
            DpopKey.htu(URI.create("https://ingest.example/v1/refinery-extract?handoff=1#top")),
        )
    }

    @Test
    fun htuDropsARedundantDefaultPortButKeepsARealOne() {
        assertEquals(
            "https://ingest.example/v1/x",
            DpopKey.htu(URI.create("https://ingest.example:443/v1/x")),
        )
        assertEquals("http://localhost/v1/x", DpopKey.htu(URI.create("http://localhost:80/v1/x")))
        assertEquals(
            "http://localhost:8080/v1/x",
            DpopKey.htu(URI.create("http://localhost:8080/v1/x")),
        )
    }

    @Test
    fun htuLowerCasesSchemeAndHostAndKeepsAnEmptyPath() {
        assertEquals("https://ingest.example/v1/x", DpopKey.htu(URI.create("HTTPS://Ingest.Example/v1/x")))
        assertEquals("https://ingest.example", DpopKey.htu(URI.create("https://ingest.example")))
    }

    // --- persistence ----------------------------------------------------------------------------

    @Test
    fun anEncodedKeyRoundTripsAndStillSignsUnderTheSameThumbprint() {
        // The refresh token outlives the process, so the key it is bound to has to as well.
        val restored = assertNotNull(DpopKey.fromEncoded(key.encoded()))
        val proof = restored.proof("POST", "https://ingest.example/v1/x")
        val (headerPart, claimsPart, signaturePart) = proof.split('.')

        assertEquals(key.thumbprint, restored.thumbprint, "a restored key must stay the same key")
        val verifier =
            Signature.getInstance("SHA256withECDSAinP1363Format").apply {
                initVerify(publicKeyFromHeader(proof))
                update("$headerPart.$claimsPart".toByteArray(Charsets.US_ASCII))
            }
        assertTrue(verifier.verify(decode(signaturePart)))
        assertContentEquals(
            key.publicJwk.toString().toByteArray(),
            restored.publicJwk.toString().toByteArray(),
        )
    }

    @Test
    fun anUnreadableEncodedKeyYieldsNullRatherThanThrowing() {
        // Fail-safe like the credential store itself: the caller then starts over with a fresh key
        // and an interactive login instead of the send flow dying on a corrupt vault entry.
        assertNull(DpopKey.fromEncoded(""))
        assertNull(DpopKey.fromEncoded("not-base64"))
        assertNull(DpopKey.fromEncoded("bm90LWEta2V5.bm90LWEta2V5"))
        assertNull(DpopKey.fromEncoded(key.encoded().substringBefore('.')))
    }

    // --- the server clock -------------------------------------------------------------------------

    @Test
    fun serverClockLearnsTheOffsetFromAnHttpDateAndAppliesItToIat() {
        val clock = ServerClock()
        assertEquals(0L, clock.offsetSeconds(), "an unmeasured clock must not shift anything")

        val sentAt = Instant.now()
        val serverTime = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(3_600)
        clock.observe(DateTimeFormatter.RFC_1123_DATE_TIME.format(serverTime), sentAt)

        assertTrue(clock.offsetSeconds() in 3_595..3_605, "was ${clock.offsetSeconds()}")
        val stamped = claim(key.proof("POST", "https://x/y", issuedAt = clock.now()), "iat")!!.toLong()
        assertTrue(
            stamped - Instant.now().epochSecond in 3_595..3_605,
            "the proof's iat must carry the correction",
        )
    }

    @Test
    fun serverClockIgnoresAnAbsentOrUnparseableDate() {
        // A server that omits Date, or a proxy that mangles it, must leave the local clock in charge
        // rather than throw the offset to some garbage value.
        val clock = ServerClock()
        clock.observe(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(600)), Instant.now())
        val learned = clock.offsetSeconds()

        clock.observe(null, Instant.now())
        clock.observe("", Instant.now())
        clock.observe("not-a-date", Instant.now())
        clock.observe("2026-08-03T12:00:00Z", Instant.now()) // ISO, not the RFC 1123 HTTP form

        assertEquals(learned, clock.offsetSeconds(), "a bad Date must not disturb what was learned")
    }

    @Test
    fun theNonceHandshakeIsDetectableButDeliberatelyUnimplemented() {
        // Kept honest: the constants exist purely so a challenge can be recognised and named. If a
        // `nonce` claim ever appears in a proof again, this change of mind should be deliberate.
        assertEquals("DPoP-Nonce", DpopNonce.HEADER)
        assertEquals("use_dpop_nonce", DpopNonce.USE_DPOP_NONCE)
        assertNull(claims(key.proof("POST", "https://x/y"))["nonce"])
    }
}
