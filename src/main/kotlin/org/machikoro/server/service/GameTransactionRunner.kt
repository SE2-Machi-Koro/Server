package org.machikoro.server.service

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Component

interface GameTransactionRunner {
    fun <T> inTransaction(action: () -> T): T
}

@Component
class ExposedGameTransactionRunner : GameTransactionRunner {
    override fun <T> inTransaction(action: () -> T): T = transaction {
        action()
    }
}
