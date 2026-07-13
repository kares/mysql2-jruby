package mysql2;

import java.sql.Timestamp;

import com.mysql.cj.protocol.Resultset;
import com.mysql.cj.protocol.ResultsetRows;
import com.mysql.cj.result.Row;

/**
 * RowSource over Connector/J's protocol-level {@link Resultset} -- the byte-native
 * path. {@link Row#getBytes} returns the raw wire bytes exactly as the caster wants,
 * with no JDBC decode/box layer in between. Only {@link #getBytes} is exercised by
 * {@link Caster}; the typed getters are not part of this path.
 */
final class CjRowSource implements RowSource {
    private final ResultsetRows rows;
    private final Field[] fields;
    private Row current;

    CjRowSource(Resultset rs) {
        com.mysql.cj.result.Field[] cjFields = rs.getColumnDefinition().getFields();
        this.fields = new Field[cjFields.length];
        for (int i = 0; i < cjFields.length; i++) this.fields[i] = Field.fromNative(cjFields[i]);
        this.rows = rs.getRows();
    }

    @Override public Field[] fields() {
        return fields;
    }

    @Override public boolean next() {
        if (rows == null || !rows.hasNext()) return false;
        current = rows.next();
        return true;
    }

    @Override public byte[] getBytes(int col) {
        return current.getNull(col) ? null : current.getBytes(col);
    }

    @Override public void close() {
        if (rows != null) rows.close();
    }

    // Not used by the caster's byte path.
    @Override public boolean wasNull() { throw new UnsupportedOperationException(); }
    @Override public long getLong(int col) { throw new UnsupportedOperationException(); }
    @Override public double getDouble(int col) { throw new UnsupportedOperationException(); }
    @Override public String getString(int col) { throw new UnsupportedOperationException(); }
    @Override public Timestamp getTimestamp(int col) { throw new UnsupportedOperationException(); }
}
