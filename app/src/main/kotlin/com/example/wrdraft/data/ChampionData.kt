package com.example.wrdraft.data

data class Champion(val name: String, val slug: String)

/**
 * Roster snapshot taken from Riot's Wild Rift champion hub on 2026-11-06.
 * Matchup data is not bundled because no trustworthy full dataset is available here.
 */
object ChampionData {
    const val ROSTER_SOURCE_URL = "https://wildrift.leagueoflegends.com/en-us/champions/"
    const val ROSTER_SNAPSHOT_DATE = "2026-11-06"

    private val displayNames = mapOf(
        "aurelion-sol" to "Aurelion Sol",
        "chogath" to "Cho'Gath",
        "dr-mundo" to "Dr. Mundo",
        "jarvan-iv" to "Jarvan IV",
        "kaisa" to "Kai'Sa",
        "khazix" to "Kha'Zix",
        "kogmaw" to "Kog'Maw",
        "ksante" to "K'Sante",
        "lee-sin" to "Lee Sin",
        "master-yi" to "Master Yi",
        "miss-fortune" to "Miss Fortune",
        "nunu-and-willump" to "Nunu & Willump",
        "twisted-fate" to "Twisted Fate",
        "velkoz" to "Vel'Koz",
        "xin-zhao" to "Xin Zhao",
    )

    private val championSlugs = listOf(
        "aatrox", "ahri", "akali", "akshan", "alistar", "ambessa", "amumu", "annie",
        "ashe", "aurelion-sol", "aurora", "bard", "blitzcrank", "brand", "braum",
        "caitlyn", "camille", "chogath", "corki", "darius", "diana", "dr-mundo",
        "draven", "ekko", "evelynn", "ezreal", "fiddlesticks", "fiora", "fizz",
        "galio", "garen", "gnar", "gragas", "graves", "gwen", "hecarim",
        "heimerdinger", "hwei", "irelia", "janna", "jarvan-iv", "jax", "jayce",
        "jhin", "jinx", "kaisa", "kalista", "karma", "kassadin", "katarina",
        "kayle", "kayn", "kennen", "khazix", "kindred", "kogmaw", "ksante",
        "lee-sin", "leona", "lillia", "lissandra", "lucian", "lulu", "lux",
        "malphite", "maokai", "master-yi", "mel", "milio", "miss-fortune",
        "mordekaiser", "morgana", "nami", "nasus", "nautilus", "nidalee", "nilah",
        "nocturne", "norra", "nunu-and-willump", "olaf", "orianna", "ornn",
        "pantheon", "poppy", "pyke", "rakan", "rammus", "rell", "renekton",
        "rengar", "riven", "rumble", "ryze", "samira", "senna", "seraphine",
        "sett", "shen", "shyvana", "singed", "sion", "sivir", "skarner",
        "smolder", "sona", "soraka", "swain", "syndra", "taliyah", "talon",
        "teemo", "thresh", "tristana", "tryndamere", "twisted-fate", "twitch",
        "urgot", "varus", "vayne", "veigar", "velkoz", "vex", "vi", "viego",
        "viktor", "vladimir", "volibear", "warwick", "wukong", "xayah", "xin-zhao",
        "yasuo", "yone", "yunara", "yuumi", "zed", "zeri", "ziggs", "zilean",
        "zoe", "zyra",
    )

    val champions: List<Champion> = championSlugs.map { slug ->
        val name = displayNames[slug] ?: slug.split("-").joinToString(" ") { part ->
            part.replaceFirstChar { it.uppercase() }
        }
        Champion(name = name, slug = slug)
    }
}
