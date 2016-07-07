package org.jetbrains.exposed.sql

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityCache
import org.jetbrains.exposed.sql.statements.Statement
import org.jetbrains.exposed.sql.statements.StatementMonitor
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.TransactionInterface
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.*

class Key<T>()
@Suppress("UNCHECKED_CAST")
open class UserDataHolder() {
    private val userdata = HashMap<Key<*>, Any?>()

    fun <T:Any> putUserData(key: Key<T>, value: T?) {
        userdata[key] = value
    }

    fun <T:Any> getUserData(key: Key<T>) : T? {
        return userdata[key] as T?
    }

    fun <T:Any> getOrCreate(key: Key<T>, init: ()->T): T {
        if (userdata.containsKey(key)) {
            return userdata[key] as T
        }

        val new = init()
        userdata[key] = new
        return new
    }
}

open class Transaction(private val transactionImpl: TransactionInterface): UserDataHolder(), TransactionInterface by transactionImpl {

    val identityQuoteString by lazy(LazyThreadSafetyMode.NONE) { db.metadata.identifierQuoteString!! }
    val extraNameCharacters by lazy(LazyThreadSafetyMode.NONE) { db.metadata.extraNameCharacters!!}
    val keywords by lazy(LazyThreadSafetyMode.NONE) { db.metadata.sqlKeywords.split(',') }

    val monitor = StatementMonitor()

    val logger = CompositeSqlLogger()

    var statementCount: Int = 0
    var duration: Long = 0
    var warnLongQueriesDuration: Long = 2000
    var debug = true
    var selectsForUpdate = false

    val statements = StringBuilder()
    // prepare statement as key and count to execution time as value
    val statementStats = hashMapOf<String, Pair<Int,Long>>()

    init {
        logger.addLogger(StdOutSqlLogger())
    }



    override fun commit() {
        val created = flushCache()
        transactionImpl.commit()
        EntityCache.invalidateGlobalCaches(created)
    }

    fun flushCache(): List<Entity<*>> {
        with(EntityCache.getOrCreate(this)) {
            val newEntities = inserts.flatMap { it.value }
            flush()
            return newEntities
        }
    }

    private fun describeStatement(delta: Long, stmt: String): String {
        return "[${delta}ms] ${stmt.take(1024)}\n\n"
    }

    fun exec(stmt: String) = exec(stmt, { })

    fun <T:Any> exec(stmt: String, transform: (ResultSet) -> T): T? {
        val type = StatementType.values().first { stmt.startsWith(it.name, true) }
        return exec(object : Statement<T>(type, emptyList()) {
            override fun PreparedStatement.executeInternal(transaction: Transaction): T? {
                when (type) {
                    StatementType.SELECT -> executeQuery()
                    else  -> executeUpdate()
                }
                return resultSet?.let { transform(it) }
            }

            override fun prepareSQL(transaction: Transaction): String = stmt

            override fun arguments(): Iterable<Iterable<Pair<ColumnType, Any?>>> = emptyList()
        })
    }

    fun <T> exec(stmt: Statement<T>): T? = exec(stmt, {it})

    fun <T, R> exec(stmt: Statement<T>, body: Statement<T>.(T) -> R): R? {
        statementCount++

        val start = System.currentTimeMillis()
        val answer = stmt.executeIn(this)
        val delta = System.currentTimeMillis() - start

        val lazySQL = lazy(LazyThreadSafetyMode.NONE) {
            answer.second.map { it.sql(this) }.distinct().joinToString()
        }

        duration += delta

        if (debug) {
            statements.append(describeStatement(delta, lazySQL.value))
            statementStats.getOrPut(lazySQL.value, { 0 to 0L }).let { pair ->
                statementStats[lazySQL.value] = (pair.first + 1) to (pair.second + delta)
            }
        }

        if (delta > warnLongQueriesDuration) {
            exposedLogger.warn("Long query: ${describeStatement(delta, lazySQL.value)}", RuntimeException())
        }

        return answer.first?.let { stmt.body(it) }
    }



    private fun String.isIdentifier() = !isEmpty() && first().isIdentifierStart() && all { it.isIdentifierStart() || it in '0'..'9' }
    private fun Char.isIdentifierStart(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this == '_' || this in extraNameCharacters

    private fun needQuotes (identity: String) : Boolean {
        return keywords.any { identity.equals(it, true) } || !identity.isIdentifier()
    }

    internal fun quoteIfNecessary (identity: String) : String {
        return (identity.split('.').map {quoteTokenIfNecessary(it)}).joinToString(".")
    }

    private fun quoteTokenIfNecessary(token: String) : String {
        return if (needQuotes(token)) "$identityQuoteString$token$identityQuoteString" else token
    }

    fun identity(table: Table): String {
        return (table as? Alias<*>)?.let { "${identity(it.delegate)} AS ${quoteIfNecessary(it.alias)}"} ?: quoteIfNecessary(table.tableName)
    }

    fun fullIdentity(column: Column<*>): String {
        return "${quoteIfNecessary(column.table.tableName)}.${quoteIfNecessary(column.name)}"
    }

    fun identity(column: Column<*>): String {
        return quoteIfNecessary(column.name)
    }

    fun prepareStatement(sql: String, autoincs: List<String>? = null): PreparedStatement {
        val flag = if (autoincs != null && autoincs.isNotEmpty())
            java.sql.Statement.RETURN_GENERATED_KEYS
        else
            java.sql.Statement.NO_GENERATED_KEYS
        return connection.prepareStatement(sql, flag)!!
    }
}

