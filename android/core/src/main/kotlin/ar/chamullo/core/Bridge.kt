package ar.chamullo.core

/**
 * El puente por Internet (Discovery & Routing §11, the Capitán's design): where the islands cannot reach (the big jump),
 * a phone that lends its Internet takes the letter up and phones of the destination barrio that also lend theirs bring
 * it down. The letter stays sealed end to end: the cloud sees what any carrier sees.
 */
interface Bridge {
    /** Phones lending Internet in [zone], in the order the directory spreads the load. */
    fun peersIn(zone: Zone): List<ByteArray>

    /** Hands a whole frame to one of those phones, over Internet. */
    fun send(to: ByteArray, frame: ByteArray)
}
