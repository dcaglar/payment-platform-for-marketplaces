package com.dogancaglar.paymentservice.infra.adapter.outbound.persistence

import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import java.sql.Connection
import javax.sql.DataSource

class DBWriterTxManager(
    dataSource: DataSource,
    private val stmtMs: Long,
    private val lockMs: Long,
    private val idleMs: Long? = 0
) : DataSourceTransactionManager(dataSource) {

    override fun prepareTransactionalConnection(
        con: Connection,
        definition: TransactionDefinition
    ) {
        // keep Spring’s default prep (isolation, read-only, etc.)
        super.prepareTransactionalConnection(con, definition)

        // transaction-local limits (rolled back with the tx; do not leak)
        con.createStatement().use { st ->
            st.execute("SET LOCAL statement_timeout = '${stmtMs}ms'")
            st.execute("SET LOCAL lock_timeout = '${lockMs}ms'")
            if (idleMs!! > 0) {
                st.execute("SET LOCAL idle_in_transaction_session_timeout = '${idleMs}ms'")
            }
        }
    }
}
