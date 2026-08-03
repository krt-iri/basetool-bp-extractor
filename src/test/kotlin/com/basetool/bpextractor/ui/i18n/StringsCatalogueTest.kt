package com.basetool.bpextractor.ui.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the two things about the string catalogues that nothing else catches.
 *
 * <p>**Class loading.** A flat `class Strings(val …)` would exceed the JVM's 254-value-parameter
 * constructor limit and be rejected at class-LOAD time with `ClassFormatError` — which
 * `compileKotlin` does not see and which, before this test, only a manual GUI launch would reveal
 * (and only for the German catalogue, since [StringsEn] loads first when someone flips the toggle).
 * Touching every entry of both objects forces both to initialise here.
 *
 * <p>**Parity.** German is the default and English must have full parity (design spec §6). Walking
 * the [Strings] interface by reflection means a new entry is covered the moment it is declared,
 * without anyone remembering to extend this test.
 */
class StringsCatalogueTest {

    private val accessors = Strings::class.java.methods.filter { it.parameterCount == 0 }

    @Test
    fun `both catalogues load and answer every entry of the interface`() {
        assertTrue(accessors.size > 100, "reflection should find the whole catalogue, got ${accessors.size}")

        for (catalogue in listOf<Strings>(StringsDe, StringsEn)) {
            val name = catalogue::class.simpleName
            for (accessor in accessors) {
                val value = assertNotNull(accessor.invoke(catalogue), "$name.${accessor.name} is null")
                when (value) {
                    is String ->
                        assertTrue(value.isNotBlank(), "$name.${accessor.name} is blank")
                    // Flat lists of strings plus the nested help tables (List<List<String>>).
                    is List<*> -> assertNoBlankLeaf(value, "$name.${accessor.name}")
                    // Lambdas and the grouped holders (SendStrings/AccountStrings): being non-null
                    // is the whole assertion — their contents are exercised below.
                    else -> Unit
                }
            }
        }
    }

    @Test
    fun `the grouped send and account holders are filled in both languages`() {
        // These two are real classes with constructors, so a missing argument is a compile error —
        // but a blank one is not, and they carry the send flow's entire user-facing vocabulary.
        for (catalogue in listOf<Strings>(StringsDe, StringsEn)) {
            val name = catalogue::class.simpleName
            with(catalogue.send) {
                listOf(button, consentTitle, consentBody, consentConfirm, authTitle, authBody,
                    authOpenBrowser, waiting, inProgress, resultTitle, resultBody, openInBasetool, saveLocally)
                    .forEach { assertTrue(it.isNotBlank(), "$name.send has a blank entry") }
                assertTrue(authCode("WXYZ-1234").contains("WXYZ-1234"))
                assertTrue(error("boom").contains("boom"))
                // The gateway's CLIENT_NOT_ALLOWED detail is a hardcoded English sentence with no
                // server-side localisation, so the extractor's own wording has to carry the
                // explanation — and still relay what the basetool said.
                assertTrue(errorClientNotAllowed("boom").contains("boom"))
                assertTrue(
                    errorClientNotAllowed("boom").length > error("boom").length,
                    "$name: a permanent refusal needs more than the generic failure line",
                )
                // The two other named failures: a nonce handshake this build does not speak, and a
                // clock too far off for any proof to land. Both must relay the server's words AND
                // say what would actually fix them.
                assertTrue(errorDpopNonceRequired("boom").contains("boom"))
                assertTrue(errorClockSkew(-42, "boom").contains("boom"))
                assertTrue(errorClockSkew(-42, "boom").contains("42"), "$name: state the measurement")
                assertTrue(errorClockSkew(42, "boom").contains("42"))
                assertTrue(
                    errorClockSkew(-42, "x") != errorClockSkew(42, "x"),
                    "$name: fast and slow must not read the same",
                )
            }
            with(catalogue.account) {
                listOf(connected, disconnected, disconnect, disconnectTitle, disconnectBody, disconnectConfirm)
                    .forEach { assertTrue(it.isNotBlank(), "$name.account has a blank entry") }
            }
        }
    }

    @Test
    fun `the parameterised export guards render their argument`() {
        for (catalogue in listOf<Strings>(StringsDe, StringsEn)) {
            assertTrue(catalogue.rfSendBlockedMissingQty(3).contains("3"))
            assertTrue(catalogue.rfExportSuccess("C:\\tmp\\x.json").contains("C:\\tmp\\x.json"))
        }
        // The two @NotEmpty guards (ADR-0008 amendment) are fixed sentences; only parity matters.
        assertNotEquals(StringsDe.rfSendBlockedNoGoods, StringsEn.rfSendBlockedNoGoods)
        assertNotEquals(StringsDe.rfSendBlockedNoSourceImages, StringsEn.rfSendBlockedNoSourceImages)
    }

    @Test
    fun `the catalogues are distinct objects with distinct wording`() {
        // A copy-paste that left StringsEn pointing at German text would otherwise pass everything.
        val differing =
            accessors.count { accessor ->
                val de = accessor.invoke(StringsDe)
                val en = accessor.invoke(StringsEn)
                de is String && en is String && de != en
            }
        assertTrue(differing > 50, "expected the catalogues to actually differ, only $differing entries did")
        assertEquals(Lang.entries.size, 2)
    }

    private fun assertNotEquals(a: String, b: String) =
        assertTrue(a != b, "the two catalogues must not share the same sentence")

    /** Walks a catalogue list (help tables nest one level) and rejects empty or blank leaves. */
    private fun assertNoBlankLeaf(value: List<*>, path: String) {
        assertTrue(value.isNotEmpty(), "$path is an empty list")
        value.forEachIndexed { index, entry ->
            when (entry) {
                is String -> assertTrue(entry.isNotBlank(), "$path[$index] is blank")
                is List<*> -> assertNoBlankLeaf(entry, "$path[$index]")
                else -> assertNotNull(entry, "$path[$index] is null")
            }
        }
    }
}
