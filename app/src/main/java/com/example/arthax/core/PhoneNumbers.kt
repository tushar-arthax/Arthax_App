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

    /**
     * True when [text] appears to carry this number — used to tell one recording file from
     * another when several calls happened within minutes of each other.
     *
     * Nearly every OEM recorder puts the number in the file name: "918197829088_2026…m4a"
     * on Xiaomi, "Call recording 8197829088_260905_141610.m4a" on Samsung, and so on. When
     * it is there it is the strongest signal available — far stronger than a timestamp,
     * which is what got two recordings swapped between two leads called a minute apart.
     *
     * Only ever used to *prefer* a file, never to reject one. File names also contain dates,
     * durations and counters, and a rule that threw a file away for holding the "wrong"
     * digits would lose recordings on any phone whose naming we had not anticipated.
     */
    fun looksLikeNumber(text: String?, matchKey: String): Boolean {
        if (matchKey.length < MATCH_DIGITS || text.isNullOrBlank()) return false

        // Digit runs, so "8197829088" inside "Call_8197829088_20260905.m4a" is seen as one
        // number rather than being spliced together with the date that follows it.
        var run = StringBuilder()
        for (char in text) {
            if (char.isDigit()) {
                run.append(char)
            } else {
                if (runHolds(run, matchKey)) return true
                run = StringBuilder()
            }
        }
        return runHolds(run, matchKey)
    }

    /**
     * A run holds the number when it ends with it — "918197829088" does, and so does a bare
     * "8197829088". A run that merely contains it somewhere in the middle does not count:
     * that is how a fourteen-digit timestamp can collide with a real number by chance.
     */
    private fun runHolds(run: CharSequence, matchKey: String): Boolean =
        run.length >= MATCH_DIGITS &&
            run.length <= MAX_DIALLED_DIGITS &&
            run.endsWith(matchKey)

    /** E.164 allows fifteen; anything longer is a timestamp or a counter, not a number. */
    private const val MAX_DIALLED_DIGITS = 15
}
