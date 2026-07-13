package mysql2;

/**
 * A forward cursor over a result: column metadata, row advancement, and raw
 * per-cell wire bytes. Extends {@link TextRow} so {@link Caster} can read the
 * current row's cells directly (the caster only uses {@link TextRow#getBytes}).
 *
 * Two implementations: {@link CjRowSource} over Connector/J's protocol-level
 * Resultset (the byte-native path used by Client#query) and {@link JdbcRowSource}
 * over a JDBC ResultSet (prepared statements, and the non-Connector/J fallback).
 */
interface RowSource extends TextRow {
    /** Column metadata, 0-based, computed once. */
    Field[] fields();

    /** Advance to the next row; false at end of result. */
    boolean next();

    /** Release the underlying result (and statement, if this source owns one). */
    void close();
}
