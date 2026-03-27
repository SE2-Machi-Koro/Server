package org.machikoro.server

import io.github.cdimascio.dotenv.Dotenv
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

fun main(args: Array<String>) {
    bootstrapAndRun(args, dotenvLoaderForMain, appRunnerForMain)
}
