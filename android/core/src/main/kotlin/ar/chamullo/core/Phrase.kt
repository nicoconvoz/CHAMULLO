package ar.chamullo.core

/**
 * Recovery phrase: 16 Spanish words (15 random bytes + 1 checksum byte), the same list and format as ICEBREAK
 * (icebreak_core/lib/src/domain/recovery.dart). CHAMULLO derives its own seed with its own label (Identity §5.3),
 * so the same phrase gives unlinkable keys in each network.
 */
object Phrase {
    const val ENTROPY = 15

    val WORDS: List<String> = listOf(
        "agua", "aire", "alba", "alma", "ancla", "arena", "arco", "arpa", "astro", "avena", "azul", "bahia",
        "balsa", "banco", "barco", "barro", "bote", "brisa", "bruma", "buzo", "cabo", "cacao", "cala", "calma",
        "campo", "canoa", "cardo", "casa", "cedro", "cielo", "cima", "cisne", "clavo", "cobre", "cofre", "coral",
        "costa", "cueva", "cumbre", "dado", "delfin", "dique", "dorado", "duna", "eco", "eje", "faro", "feria",
        "fibra", "flor", "foca", "forja", "fruta", "fuego", "gaviota", "globo", "gota", "grano", "grillo", "gruta",
        "haba", "hielo", "hoja", "hongo", "horno", "huerto", "humo", "iglu", "isla", "jade", "jarra", "joya",
        "junco", "lago", "lana", "lapiz", "laurel", "lazo", "leche", "lente", "leon", "lima", "lince", "lino",
        "llave", "lobo", "loro", "luna", "madera", "mango", "manta", "mapa", "mar", "marea", "mate", "menta",
        "mesa", "miel", "mina", "mirlo", "molino", "monte", "mora", "morsa", "muelle", "nube", "nido", "niebla",
        "noche", "norte", "nuez", "oasis", "ola", "olivo", "onda", "oro", "oso", "ostra", "pala", "palma",
        "pan", "papel", "pato", "perla", "pez", "piedra", "pino", "pista", "plata", "playa", "pluma", "polo",
        "pozo", "puente", "puerto", "pulpo", "puma", "quinoa", "radar", "rama", "rana", "rayo", "red", "remo",
        "rio", "roble", "roca", "rosa", "rueda", "sal", "salvia", "sauce", "selva", "sello", "seta", "sierra",
        "sol", "sombra", "soplo", "sur", "tabla", "tejo", "tela", "tierra", "tigre", "timon", "toro", "torre",
        "trigo", "trueno", "tulipan", "uva", "vapor", "vela", "verde", "viento", "vino", "violeta", "volcan", "yate",
        "yerba", "yeso", "zorro", "abeja", "acero", "adobe", "aguila", "album", "alerce", "algas", "almendra", "ambar",
        "anzuelo", "apio", "arbol", "ardilla", "arroz", "atlas", "avion", "bambu", "bandera", "barril", "baul", "bosque",
        "botella", "brujula", "burbuja", "caballo", "cactus", "cadena", "camino", "canela", "cantaro", "carbon", "castor", "cebra",
        "cereza", "chispa", "ciervo", "circo", "cometa", "concha", "cordero", "corona", "cristal", "cuarzo", "cuerda", "dragon",
        "escudo", "espiga", "estrella", "faisan", "flauta", "gacela", "galleta", "gigante", "girasol", "granja", "guitarra", "halcon",
        "helecho", "hormiga", "jaguar", "jardin", "jirafa", "koala", "lechuza", "limon", "linterna", "llama", "loma", "lucero",
        "manzana", "marfil", "medalla", "mochila",
    )

    private val accents = mapOf('á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u', 'ü' to 'u', 'ñ' to 'n', 'à' to 'a', 'è' to 'e', 'ì' to 'i', 'ò' to 'o', 'ù' to 'u')

    fun normalize(phrase: String): List<String> =
        phrase.lowercase().map { accents[it] ?: it }.joinToString("").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun newPhrase(entropy: ByteArray = Crypto.randomBytes(ENTROPY)): String {
        require(entropy.size == ENTROPY)
        return (entropy.toList() + Crypto.sha512(entropy)[0]).joinToString(" ") { WORDS[it.toInt() and 0xff] }
    }

    sealed interface Check {
        data object Ok : Check
        data object WrongLength : Check
        data class UnknownWord(val word: String) : Check
        data object BadChecksum : Check
    }

    fun check(phrase: String): Check {
        val w = normalize(phrase)
        if (w.size != ENTROPY + 1) return Check.WrongLength
        w.firstOrNull { it !in WORDS }?.let { return Check.UnknownWord(it) }
        val idx = w.map { WORDS.indexOf(it) }
        val e = ByteArray(ENTROPY) { idx[it].toByte() }
        return if ((Crypto.sha512(e)[0].toInt() and 0xff) == idx[ENTROPY]) Check.Ok else Check.BadChecksum
    }

    fun seedOf(phrase: String): ByteArray =
        Crypto.sha512(("chamullo-recovery-v1 " + normalize(phrase).joinToString(" ")).toByteArray()).copyOf(32)
}
