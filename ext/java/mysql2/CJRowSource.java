package mysql2;

import com.mysql.cj.protocol.ColumnDefinition;
import com.mysql.cj.protocol.Resultset;
import com.mysql.cj.protocol.ResultsetRows;
import com.mysql.cj.result.Row;

/**
 * RowSource over Connector/J's protocol-level {@link Resultset} - the byte-native path,
 * {@link Row#getBytes} returns the raw wire bytes, without any JDBC decode/box layer.
 */
final class CJRowSource implements RowSource {
    private final ResultsetRows rows;
    private final ColumnDefinition columnDefinition;
    private Row current;

    CJRowSource(Resultset rs) {
        this.columnDefinition = rs.getColumnDefinition();
        this.rows = rs.getRows();
    }

    @Override
    public int size() {
        return columnDefinition.getFields().length;
    }

    @Override
    public com.mysql.cj.result.Field getField(final int col) {
        return columnDefinition.getFields()[col];
    }

    @Override
    public String fieldName(final int col) {
        final com.mysql.cj.result.Field field = getField(col);
        // Connector/J returns null for a zero-length column label, which it produces
        // when the name bytes can't be decoded (e.g. invalid-encoding SELECT literal)
        final String name = field.getName();
        return name == null ? "" : name;
    }

    @Override
    public boolean next() {
        if (rows == null || !rows.hasNext()) return false;
        current = rows.next();
        return true;
    }

    @Override
    public void reset() {
        current = null;
        if (rows != null) rows.beforeFirst();
    }

    @Override
    public byte[] getBytes(int col) {
        return current.getBytes(col); // null if wasNull()
    }

    @Override
    public void close() {
        if (rows != null) rows.close();
    }
}
