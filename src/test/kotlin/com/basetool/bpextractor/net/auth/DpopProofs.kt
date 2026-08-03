package com.basetool.bpextractor.net.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.Base64

/**
 * Reads a compact DPoP proof back the way a server does, so the HTTP-level suites can assert on what
 * actually went over the wire. Signature verification and the RFC 7638 thumbprint live in
 * [DpopTest]; this is only the decoding those tests would otherwise each repeat.
 */
object DpopProofs {

    /** The proof's JOSE header (`typ`, `alg`, `jwk`). */
    fun header(proof: String): JsonObject = part(proof, 0)

    /** The proof's claim set (`jti`, `htm`, `htu`, `iat`, and optionally `ath` / `nonce`). */
    fun claims(proof: String): JsonObject = part(proof, 1)

    /**
     * One claim as a string.
     *
     * @param proof the compact proof
     * @param name the claim name
     * @return the value, or `null` when the claim is absent
     */
    fun claim(proof: String, name: String): String? =
        claims(proof)[name]?.jsonPrimitive?.content

    /**
     * The `ath` an honest proof for [accessToken] must carry (RFC 9449 §4.2).
     *
     * @param accessToken the token the proof accompanies
     * @return base64url of the SHA-256 over the token's ASCII bytes
     */
    fun expectedAth(accessToken: String): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray(Charsets.US_ASCII)),
            )

    private fun part(proof: String, index: Int): JsonObject =
        Json.parseToJsonElement(
            Base64.getUrlDecoder().decode(proof.split('.')[index]).decodeToString(),
        )
            .jsonObject
}
