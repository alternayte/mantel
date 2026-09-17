package com.mantel.kernel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.transactions.transaction

/** JDBC blocks. Handlers do not, so every database call leaves the request thread. */
suspend fun <T> db(block: () -> T): T = withContext(Dispatchers.IO) { transaction { block() } }
