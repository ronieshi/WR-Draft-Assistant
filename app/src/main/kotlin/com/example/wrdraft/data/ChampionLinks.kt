package com.example.wrdraft.data

/** Source URLs for champion data that the app presents natively and caches locally. */
object ChampionLinks {
    private val wildRiftCoreSlugs = mapOf(
        "chogath" to "cho-gath",
        "kaisa" to "kai-sa",
        "khazix" to "kha-zix",
        "kogmaw" to "kog-maw",
        "ksante" to "k-sante",
        "nunu-and-willump" to "nunu-willump",
        "velkoz" to "vel-koz",
    )

    private fun wildRiftCoreSlug(slug: String): String = wildRiftCoreSlugs[slug] ?: slug
    fun wildRiftCoreBuildUrl(slug: String): String =
        "https://wildriftcore.com/en/champions/${wildRiftCoreSlug(slug)}/builds/"

    fun wildRiftCoreProfileUrl(slug: String): String =
        "https://wildriftcore.com/en/champions/${wildRiftCoreSlug(slug)}/"

    fun wildRiftCoreCountersUrl(slug: String): String =
        "https://wildriftcore.com/en/champions/${wildRiftCoreSlug(slug)}/counters/"

    fun wildRiftCoreSynergiesUrl(slug: String): String =
        "https://wildriftcore.com/en/champions/${wildRiftCoreSlug(slug)}/synergies/"

    fun championIconUrl(slug: String): String =
        "https://wildriftcore.com/assets/champions/icons/${wildRiftCoreSlug(slug)}.webp"

}
