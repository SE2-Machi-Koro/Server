package org.machikoro.server

import io.github.cdimascio.dotenv.Dotenv
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ServerApplication

internal fun loadDotenv(): Dotenv = Dotenv.configure().ignoreIfMissing().load()

internal fun applyDotenvToSystemProperties(dotenv: Dotenv) {
    dotenv.entries().forEach { entry ->
        System.setProperty(entry.key, entry.value)
    }
}

internal var dotenvLoaderForMain: () -> Dotenv = ::loadDotenv
internal var appRunnerForMain: (Array<String>) -> Unit = { runApplication<ServerApplication>(*it) }

internal fun bootstrapAndRun(
    args: Array<String>,
    dotenvLoader: () -> Dotenv,
    runner: (Array<String>) -> Unit
) {
    val dotenv = dotenvLoader()
    applyDotenvToSystemProperties(dotenv)
    runner(args)
}

fun connectToDatabase() {
    val dotenv = dotenvLoaderForMain()

    val host = dotenv["DB_HOST"] ?: System.getenv("DB_HOST") ?: "localhost"
    val port = dotenv["DB_PORT"] ?: System.getenv("DB_PORT") ?: "5432"
    val dbName = dotenv["DB_NAME"] ?: System.getenv("DB_NAME") ?: "machikoro"
    val user = dotenv["DB_USERNAME"] ?: System.getenv("DB_USERNAME") ?: throw IllegalArgumentException("Missing DB_USERNAME")
    val password = dotenv["DB_PASSWORD"] ?: System.getenv("DB_PASSWORD") ?: throw IllegalArgumentException("Missing DB_Password")

    val jdbcUrl = "jdbc:postgresql://$host:$port/$dbName"

    Database.connect(
        url = jdbcUrl,
        driver = "org.postgresql.Driver",
        user = user,
        password = password
    )
}

fun main(args: Array<String>) {
    bootstrapAndRun(args, dotenvLoaderForMain, appRunnerForMain)
    connectToDatabase()
}
