package mysql2;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Adapter over a live JDBC ResultSet (e.g. MariaDB Connector/J on the text
 * protocol). This is the drop-in used against a real server; the byte path
 * calls getBytes(), the comparison path calls the typed getters.
 *
 * JDBC columns are 1-based; TextRow is 0-based.
 */
public final class JdbcTextRow implements TextRow {
    private final ResultSet resultSet;

    public JdbcTextRow(ResultSet resultSet) {
        this.resultSet = resultSet;
    }

    private static RuntimeException wrap(SQLException e) {
        return new RuntimeException(e); // TODO
    }

    @Override public boolean wasNull() {
        try { return resultSet.wasNull(); } catch (SQLException e) { throw wrap(e); }
    }
    @Override public byte[] getBytes(int col) {
        try { return resultSet.getBytes(col + 1); } catch (SQLException e) { throw wrap(e); }
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
