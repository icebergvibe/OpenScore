package org.openscore.model

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerNamesTest {
    @Test
    fun normalizesWhitespaceAndAllCapsWithoutGuessingNameOrder() {
        assertEquals("Marcello Trotta", PlayerNames.display("  MARCELLO   TROTTA "))
        assertEquals("Trotta Marcello", PlayerNames.display("TROTTA Marcello"))
    }

    @Test
    fun movesAProviderMarkedLeadingCapsFamilyNameAfterTheGivenName() {
        assertEquals("Marcello Trotta", PlayerNames.display("TROTTA Marcello", leadingCapsAreFamilyName = true))
        assertEquals("Pah Franck Gouhon", PlayerNames.display("GOUHON Pah Franck", leadingCapsAreFamilyName = true))
        assertEquals("Vinicius Mendonca Santa Rosa", PlayerNames.display("MENDONCA SANTA ROSA Vinicius", leadingCapsAreFamilyName = true))
    }

    @Test
    fun usesFullStructuredNameInsteadOfShortFallback() {
        assertEquals("Pablo Sisniega", PlayerNames.fromParts("Pablo", "Sisniega", "P. Sisniega"))
    }

    @Test
    fun playerReferencesAlwaysReceiveBasicDisplayNormalization() {
        assertEquals("Marcello Trotta", PlayerRef("malta-premier", "1", "MARCELLO TROTTA").name)
    }
}
