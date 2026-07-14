package mysql2;

import com.mysql.cj.result.Field;

/**
 * A forward cursor over a result: column metadata, row advancement, and raw
 * per-cell wire bytes. Extends {@link TextRow} so {@link Caster} can read the
 * current row's cells directly (the caster only uses {@link TextRow#getBytes}).
 *
 * Two implementations: {@link CJRowSource} over Connector/J's protocol-level
 * Resultset (the byte-native path used by Client#query) and {@link JdbcRowSource}
 * over a JDBC ResultSet (prepared statements, and the non-Connector/J fallback).
 */
interface RowSource extends TextRow {
    int size();

    /** Advance to the next row; false at end of result. */
    boolean next();

    /** Release the underlying result (and statement, if this source owns one). */
    void close();

    /** Column metadata, 0-based, computed once. */
    // Field[] fields();

    Field getField(int col);

    default String fieldName(final int col) {
        return getField(col).getName();
    }

    default String typeName(final int col) {
        return mysql2.Field.typeName(getField(col));
    }
}
