package ar.chamullo.relay

/** Hosts el relé: ./gradlew :relay:run  →  http://<this machine>:47475 */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 47_475
    RelayServer().start(port)
    println("Relé CHAMULLO escuchando en el puerto $port")
}
