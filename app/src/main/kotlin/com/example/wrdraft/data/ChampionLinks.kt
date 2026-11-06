package com.example.wrdraft.data

/**
 * Links to external champion data. Matchups are not copied into the app because
 * their source is patch-dependent and the repository has no full local dataset.
 */
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
    private fun wildRiftFireSlug(slug: String): String =
        if (slug == "nunu-and-willump") "nunu-willump" else slug

    fun wildRiftCoreBuildUrl(slug: String): String =
        "https://wildriftcore.com/en/champions/${wildRiftCoreSlug(slug)}/builds/"

    fun wildRiftCoreCountersUrl(slug: String): String =
        "https://wildriftcore.com/en/champions/${wildRiftCoreSlug(slug)}/counters/"

    fun wildRiftCoreSynergiesUrl(slug: String): String =
        "https://wildriftcore.com/en/champions/${wildRiftCoreSlug(slug)}/synergies/"

    fun wildRiftFireUrl(slug: String): String =
        "https://www.wildriftfire.com/guide/${wildRiftFireSlug(slug)}"
}
