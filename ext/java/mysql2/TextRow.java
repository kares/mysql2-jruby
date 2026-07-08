package mysql2;

import java.sql.Timestamp;

/**
 * Column accessors the caster needs. A real MySQL text-protocol JDBC ResultSet
 * satisfies this via {@link JdbcTextRow}; the benchmark uses {@link FakeTextRow}.
 *
 * getBytes(i) returns the raw wire bytes (text protocol: ASCII digits / charset
 * bytes), which is the cheap path. The typed getters model the driver's own
 * decode+box work that the byte path aims to avoid.
 */
public interface TextRow {
    byte[] getBytes(int col);     // raw wire bytes, or null for SQL NULL (0-based)

    boolean wasNull();            // whether the last typed getter read SQL NULL

    long getLong(int col);        // driver-decoded + parsed + boxed as long

    double getDouble(int col);

    String getString(int col);    // driver charset-decodes to a Java String

    Timestamp getTimestamp(int col);
}
