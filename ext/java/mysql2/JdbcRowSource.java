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
 *
 * JDBC columns are 1-based; RowSource is 0-based. {@code statementToClose} is
 * closed with the result (pass null to leave a reusable PreparedStatement open).
 */
final class JdbcRowSource implements RowSource {
    private final ResultSet resultSet;
    private final Statement statementToClose;
    private final Field[] fields;

    JdbcRowSource(ResultSet resultSet, Statement statementToClose) throws SQLException {
        this.resultSet = resultSet;
        this.statementToClose = statementToClose;

        ResultSetMetaData md = resultSet.getMetaData();
        int count = md.getColumnCount();
        this.fields = new Field[count];
        // Prefer Connector/J's native field metadata (real MySQL type/length/flags);
        // fall back to JDBC types for any other driver.
        com.mysql.cj.result.Field[] nativeFields = null;
        if (md instanceof com.mysql.cj.jdbc.result.ResultSetMetaData) {
            nativeFields = ((com.mysql.cj.jdbc.result.ResultSetMetaData) md).getFields();
        }
        for (int i = 0; i < count; i++) {
            fields[i] = nativeFields != null ? Field.fromNative(nativeFields[i]) : Field.fromMeta(md, i + 1);
        }
    }

    private static RuntimeException wrap(SQLException e) {
        return new RuntimeException(e);
    }

    @Override public Field[] fields() {
        return fields;
    }

    @Override public boolean next() {
        try { return resultSet.next(); } catch (SQLException e) { throw wrap(e); }
    }

    @Override public void close() {
        try { if (resultSet != null) resultSet.close(); } catch (SQLException ignore) { }
        try { if (statementToClose != null) statementToClose.close(); } catch (SQLException ignore) { }
    }

    @Override public byte[] getBytes(int col) {
        try { return resultSet.getBytes(col + 1); } catch (SQLException e) { throw wrap(e); }
    }
    @Override public boolean wasNull() {
        try { return resultSet.wasNull(); } catch (SQLException e) { throw wrap(e); }
    }
    @Override public long getLong(int col) {
        try { return resultSet.getLong(col + 1); } catch (SQLException e) { throw wrap(e); }
    }
    @Override public double getDouble(int col) {
        try { return resultSet.getDouble(col + 1); } catch (SQLException e) { throw wrap(e); }
    }
    @Override public String getString(int col) {
        try { return resultSet.getString(col + 1); } catch (SQLException e) { throw wrap(e); }
    }
    @Override public Timestamp getTimestamp(int col) {
        try { return resultSet.getTimestamp(col + 1); } catch (SQLException e) { throw wrap(e); }
    }
}
