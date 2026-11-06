package com.example.wrdraft.data

/**
 * Enlaces a páginas externas de builds. La app no copia su contenido: solo abre el navegador.
 */
object ChampionLinks {
    // Nombre en minúsculas, sin apóstrofes, espacios a guion (p. ej. "Xin Zhao" -> "xin-zhao").
    // Formato comprobado en WildRiftCore (Ahri, Varus, Ashe) y en WildRiftFire (Warwick).
    // Nombres con apóstrofe o caracteres especiales (Kai'Sa, Cho'Gath) no están verificados.
    private fun slug(champion: String): String =
        champion.trim().lowercase()
            .replace("'", "")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    // wildriftcore.com: /en/champions/{slug}/builds/ (visto para Ahri, Varus y Ashe).
    fun wildriftCoreBuildUrl(champion: String): String =
        "https://wildriftcore.com/en/champions/${slug(champion)}/builds/"

    // wildriftfire.com: /guide/{slug} (visto para Warwick).
    fun wildRiftFireUrl(champion: String): String =
        "https://www.wildriftfire.com/guide/${slug(champion)}"
}
