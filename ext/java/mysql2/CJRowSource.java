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
        final com.mysql.cj.result.Field cjField = columnDefinition.getFields()[col];
        // Connector/J returns null for a zero-length column label, which it produces
        // when the name bytes can't be decoded (e.g. an invalid-encoding SELECT literal).
        final String name = cjField.getName();
        return name == null ? "" : name;
    }

    @Override
    public String typeName(final int col) {
        return Field.typeName(columnDefinition.getFields()[col]);
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
        return current.getNull(col) ? null : current.getBytes(col);
    }

    @Override
    public void close() {
        if (rows != null) rows.close();
    }
}
