package mysql2;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;

/**
 * RowSource over a JDBC ResultSet -- used for prepared-statement results and as a
 * fallback for non-Connector/J drivers. Column bytes come from getBytes(); the
 * typed getters delegate to the driver (kept for parity, not used by the caster).
 * <p>
 * JDBC columns are 1-based; RowSource is 0-based. {@code statementToClose} is
 * closed with the result (pass null to leave a reusable PreparedStatement open).
 */
final class JdbcRowSource implements RowSource {
    private final ResultSet resultSet;
    private final Statement statementToClose;
    private final com.mysql.cj.result.Field[] fields;

    JdbcRowSource(ResultSet resultSet, Statement statementToClose) throws SQLException {
        this.resultSet = resultSet;
        this.statementToClose = statementToClose;

        ResultSetMetaData metaData = resultSet.getMetaData();
        // Prefer Connector/J's native field metadata (real MySQL type/length/flags)
        com.mysql.cj.result.Field[] nativeFields;
        if (metaData instanceof com.mysql.cj.jdbc.result.ResultSetMetaData) {
            nativeFields = ((com.mysql.cj.jdbc.result.ResultSetMetaData) metaData).getFields();
        } else {
            throw new UnsupportedOperationException("can not extract native result-set metadata for " + metaData.getClass());
        }

        this.fields = nativeFields;
    }

    @SuppressWarnings("unchecked")
    static <E extends Throwable> void throwException(Throwable ex) throws E {
        throw (E) ex;
    }

    @Override
    public int size() {
        try {
            return resultSet.getMetaData().getColumnCount();
        } catch (SQLException e) {
            throwException(e); return 0;
        }
    }

    @Override
    public com.mysql.cj.result.Field getField(final int col) {
        return fields[col];
    }

    @Override
    public boolean next() {
        try {
            return resultSet.next();
        } catch (SQLException e) {
            throwException(e); return false;
        }
    }

    @Override
    public void reset() {
        try {
            resultSet.beforeFirst();
        } catch (SQLException e) {
            throwException(e);
        }
    }

    @Override
    public void close() {
        try {
            if (resultSet != null) resultSet.close();
        } catch (SQLException ignore) {
        }
        try {
            if (statementToClose != null) statementToClose.close();
        } catch (SQLException ignore) {
        }
    }

    @Override
    public byte[] getBytes(int col) {
        try {
            return resultSet.getBytes(col + 1);
        } catch (SQLException e) {
            throwException(e); return null;
        }
    }

    public boolean wasNull() {
        try {
            return resultSet.wasNull();
        } catch (SQLException e) {
            throwException(e); return false;
        }
    }

    public long getLong(int col) {
        try {
            return resultSet.getLong(col + 1);
        } catch (SQLException e) {
            throwException(e); return 0;
        }
    }

    public double getDouble(int col) {
        try {
            return resultSet.getDouble(col + 1);
        } catch (SQLException e) {
            throwException(e); return 0;
        }
    }
}
