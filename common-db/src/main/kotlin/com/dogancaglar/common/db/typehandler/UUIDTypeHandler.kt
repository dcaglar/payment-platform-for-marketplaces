package com.dogancaglar.common.db.typehandler

import org.apache.ibatis.type.BaseTypeHandler
import org.apache.ibatis.type.JdbcType
import java.sql.CallableStatement
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID

class UUIDTypeHandler : BaseTypeHandler<UUID>() {
    override fun setNonNullParameter(ps: PreparedStatement, i: Int, parameter: UUID, jdbcType: JdbcType?) {
        ps.setObject(i, parameter, Types.OTHER)
    }

    override fun getNullableResult(rs: ResultSet, columnName: String): UUID? {
        val value = rs.getObject(columnName)
        return if (value is UUID) value else value?.toString()?.let { UUID.fromString(it) }
    }

    override fun getNullableResult(rs: ResultSet, columnIndex: Int): UUID? {
        val value = rs.getObject(columnIndex)
        return if (value is UUID) value else value?.toString()?.let { UUID.fromString(it) }
    }

    override fun getNullableResult(cs: CallableStatement, columnIndex: Int): UUID? {
        val value = cs.getObject(columnIndex)
        return if (value is UUID) value else value?.toString()?.let { UUID.fromString(it) }
    }
}
