package com.example.wrdraft.data

/**
 * Relaciones de un campeón.
 * counters: campeones que tienen ventaja contra este campeón.
 * synergies: campeones que combinan bien con este campeón.
 */
data class Matchups(
    val counters: List<String>,
    val synergies: List<String>,
)

/**
 * Dataset local de Wild Rift. Es un conjunto de ejemplo: sustitúyelo por datos
 * verificados del parche actual. Las claves deben coincidir con los nombres de [champions].
 */
object ChampionData {
    val champions: List<String> = listOf(
        "Ahri", "Akali", "Garen", "Jax", "Jinx", "Leona",
        "Lulu", "Lux", "Malphite", "Sona", "Vayne", "Yasuo",
    )

    private val matchups: Map<String, Matchups> = mapOf(
        "Ahri" to Matchups(
            counters = listOf("Lux", "Malphite"),
            synergies = listOf("Jax", "Leona"),
        ),
        "Akali" to Matchups(
            counters = listOf("Garen", "Malphite"),
            synergies = listOf("Malphite", "Leona"),
        ),
        "Garen" to Matchups(
            counters = listOf("Lux", "Vayne"),
            synergies = listOf("Sona", "Lulu"),
        ),
        "Jax" to Matchups(
            counters = listOf("Akali", "Yasuo"),
            synergies = listOf("Lulu", "Sona"),
        ),
        "Jinx" to Matchups(
            counters = listOf("Yasuo", "Akali"),
            synergies = listOf("Leona", "Lulu"),
        ),
        "Leona" to Matchups(
            counters = listOf("Lulu", "Sona"),
            synergies = listOf("Jinx", "Lux"),
        ),
        "Lulu" to Matchups(
            counters = listOf("Vayne", "Akali"),
            synergies = listOf("Jinx", "Garen"),
        ),
        "Lux" to Matchups(
            counters = listOf("Ahri", "Jax"),
            synergies = listOf("Sona", "Malphite"),
        ),
        "Malphite" to Matchups(
            counters = listOf("Vayne", "Yasuo"),
            synergies = listOf("Ahri", "Akali"),
        ),
        "Sona" to Matchups(
            counters = listOf("Akali", "Yasuo"),
            synergies = listOf("Garen", "Lux"),
        ),
        "Vayne" to Matchups(
            counters = listOf("Malphite", "Leona"),
            synergies = listOf("Lulu", "Sona"),
        ),
        "Yasuo" to Matchups(
            counters = listOf("Malphite", "Sona"),
            synergies = listOf("Malphite", "Leona"),
        ),
    )

    fun matchupsFor(champion: String): Matchups? = matchups[champion]
}
