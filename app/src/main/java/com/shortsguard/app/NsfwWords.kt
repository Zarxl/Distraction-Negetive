package com.shortsguard.app

/**
 * Hidden filter: when "Hide YouTube Shorts" is ON, any video whose title contains
 * one of these words is also blocked. Edit this list to add or remove words.
 * Whole-word, case-insensitive match (so "hot" does not match "hotel").
 */
object NsfwWords {
    private val words = listOf(
        "sex", "sexy", "sexual", "sexier", "sexiest",
        "kiss", "kisses", "kissing", "kissed",
        "hot", "lesbian", "lesbians", "vampire", "vampires",
        "nsfw", "porn", "porno", "xxx", "nude", "nudes", "naked",
        "onlyfans", "boobs", "twerk", "twerking", "lingerie",
        "erotic", "hentai", "horny", "stripper", "striptease",
        "seduce", "seductive", "makeout", "fetish", "bdsm"
    )

    private val regex = Regex(
        "\\b(" + words.joinToString("|") { Regex.escape(it) } + ")\\b",
        RegexOption.IGNORE_CASE
    )

    fun matches(text: String): Boolean = regex.containsMatchIn(text)
}
