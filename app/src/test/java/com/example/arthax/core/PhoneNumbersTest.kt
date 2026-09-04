package com.example.arthax.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Number matching decides whether a call log entry is recognised as the call we placed.
 * Get it wrong and every call silently falls back to guesswork, because the dialler almost
 * never hands the number back in the form it was given.
 */
class PhoneNumbersTest {

    @Test
    fun `the same number in the many forms a dialler produces all match`() {
        val forms = listOf(
            "7499639387",
            "+917499639387",
            "917499639387",
            "07499639387",
            "+91 74996 39387",
            "+91-74996-39387",
            "(074) 9963-9387",
        )

        forms.forEach { form ->
            assertTrue("$form should match the lead's number", PhoneNumbers.sameNumber(form, "7499639387"))
        }
    }

    @Test
    fun `different numbers do not match`() {
        assertFalse(PhoneNumbers.sameNumber("7499639387", "7499639388"))
        assertFalse(PhoneNumbers.sameNumber("+919876543210", "+919812345678"))
    }

    @Test
    fun `blank and null never match anything, including each other`() {
        // Otherwise a call log row with a withheld number would match every lead.
        assertFalse(PhoneNumbers.sameNumber(null, null))
        assertFalse(PhoneNumbers.sameNumber("", ""))
        assertFalse(PhoneNumbers.sameNumber("7499639387", null))
        assertFalse(PhoneNumbers.sameNumber("private", "7499639387"))
    }

    @Test
    fun `match key is the last ten digits`() {
        assertEquals("7499639387", PhoneNumbers.matchKey("+91 74996 39387"))
        assertEquals("7499639387", PhoneNumbers.matchKey("7499639387"))
    }

    @Test
    fun `short numbers are kept whole rather than padded`() {
        // Short codes and extensions still need to compare consistently with themselves.
        assertEquals("12345", PhoneNumbers.matchKey("12345"))
        assertTrue(PhoneNumbers.sameNumber("12345", "12345"))
    }

    @Test
    fun `api format strips the country code the backend does not expect`() {
        assertEquals("7499639387", PhoneNumbers.apiFormat("+91 7499639387"))
        assertEquals("7499639387", PhoneNumbers.apiFormat("7499639387"))
    }
}
