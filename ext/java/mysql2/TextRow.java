package mysql2;

/**
 * Column accessors the caster needs, over the current row of a {@link RowSource}
 * (Connector/J's protocol Resultset via {@link CJRowSource}, or a JDBC ResultSet
 * via {@link JdbcRowSource}).
 *
 * getBytes(i) returns the raw wire bytes (text protocol: ASCII digits / charset
 * bytes), which is the cheap path the caster uses. The typed getters model the
 * driver's own decode+box work the byte path avoids (used by the benchmark).
 */
public interface TextRow {
    byte[] getBytes(int col); // raw wire bytes, or null for SQL NULL (0-based)
    //boolean wasNull();

    //long getLong(int col); // driver-decoded + parsed + boxed as long
    //double getDouble(int col);
    //String getString(int col); // driver charset-decodes to a Java String
}
