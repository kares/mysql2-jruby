package mysql2;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyClass;
import org.jruby.RubyHash;
import org.jruby.runtime.builtin.IRubyObject;

/**
 * Materializes rows of a text-protocol JDBC ResultSet into Ruby objects,
 * doing the hot per-cell casting in Java (see {@link Caster}). One instance
 * per Mysql2::Result; Ruby drives it via nextRow() until it returns nil.
 */
final class RowReader {
    private final ResultSet resultSet;
    private final JdbcTextRow row;
    private final Field[] fields;
    private final IRubyObject[] keys; // hash keys (String or Symbol), null when asArray
    private final int count;
    private final boolean asArray;
    private final boolean cast;
    private final CastCtx ctx;

    public RowReader(Ruby runtime,
                     ResultSet resultSet,
                     boolean asArray,
                     boolean cast,
                     boolean castBool,
                     boolean symbolizeKeys,
                     String dbTimezone,
                     String appTimezone,
                     RubyClass errorClass,
                     IRubyObject localOffset) throws SQLException {
        this.resultSet = resultSet;
        this.row = new JdbcTextRow(resultSet);
        this.asArray = asArray;
        this.cast = cast;
        this.ctx = new CastCtx(runtime, castBool, dbTimezone, appTimezone, errorClass, localOffset);

        ResultSetMetaData md = resultSet.getMetaData();
        this.count = md.getColumnCount();
        this.fields = new Field[count];
        this.keys = asArray ? null : new IRubyObject[count];

        // Prefer Connector/J's native field metadata (real MySQL type/length/flags);
        // fall back to JDBC types for any other driver.
        com.mysql.cj.result.Field[] nativeFields = null;
        if (md instanceof com.mysql.cj.jdbc.result.ResultSetMetaData) {
            nativeFields = ((com.mysql.cj.jdbc.result.ResultSetMetaData) md).getFields();
        }

        for (int i = 0; i < count; i++) {
            fields[i] = nativeFields != null ? Field.fromNative(nativeFields[i]) : Field.fromMeta(md, i + 1);
            if (keys != null) {
                keys[i] = symbolizeKeys ? runtime.newSymbol(fields[i].name) : runtime.newString(fields[i].name);
            }
        }
    }

    /** Next row as a RubyHash/RubyArray, or nil at end of result. */
    public IRubyObject nextRow(final Ruby runtime) {
        try {
            if (!resultSet.next()) return runtime.getNil();
        } catch (SQLException e) {
            throw runtime.newRuntimeError(e.getMessage());
        }

        IRubyObject[] vals = new IRubyObject[count];
        for (int i = 0; i < count; i++) {
            vals[i] = cast ? Caster.byteCast(ctx, row, i, fields[i])
                           : Caster.raw(ctx, row, i, fields[i]);
        }

        if (asArray) return RubyArray.newArray(runtime, vals);

        RubyHash hash = RubyHash.newHash(runtime);
        for (int i = 0; i < count; i++) hash.fastASet(keys[i], vals[i]);
        return hash;
    }

    /** Field names (Mysql2::Result#fields), always as frozen strings. */
    IRubyObject[] fieldNames(final Ruby runtime) {
        IRubyObject[] names = new IRubyObject[count];
        for (int i = 0; i < count; i++) {
            names[i] = runtime.freezeAndDedupString(runtime.newString(fields[i].name));
        }
        return names;
    }

    /** MySQL type descriptor strings (Mysql2::Result#field_types). */
    IRubyObject[] fieldTypes(final Ruby runtime) {
        IRubyObject[] types = new IRubyObject[count];
        for (int i = 0; i < count; i++) types[i] = runtime.newString(fields[i].typeName());
        return types;
    }

    int fieldCount() {
        return count;
    }
}
