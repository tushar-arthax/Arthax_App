package com.example.arthax.core

/** Phone-number handling shared by the dialler, the call log lookup and the API. */
object PhoneNumbers {

    /** How many trailing digits identify a number locally. */
    const val MATCH_DIGITS = 10

    /**
     * A comparable key for "is this the same number".
     *
     * Never compare dialled numbers as raw strings. The dialler rewrites what it is handed —
     * adding +91, dropping a leading 0, inserting spaces or dashes — so the number that
     * comes back out of the call log rarely matches the one stored on the lead character
     * for character. The last ten digits do match.
     */
    fun matchKey(raw: String?): String =
        raw.orEmpty().filter(Char::isDigit).takeLast(MATCH_DIGITS)

    fun sameNumber(a: String?, b: String?): Boolean {
        val keyA = matchKey(a)
        val keyB = matchKey(b)
        return keyA.isNotEmpty() && keyA == keyB
    }

    /** Digits only, trimmed to the local number the backend matches on. */
    fun apiFormat(raw: String): String = matchKey(raw)
}
