package mysql2;

import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyClass;
import org.jruby.RubyHash;
import org.jruby.runtime.builtin.IRubyObject;

/**
 * Materializes rows of a text-protocol result into Ruby objects, doing the hot
 * per-cell casting in Java (see {@link Caster}). Rows and column metadata come
 * from a {@link RowSource} (Connector/J protocol Resultset, or a JDBC ResultSet).
 * One instance per Mysql2::Result; Ruby drives it via nextRow() until it returns nil.
 */
final class RowReader {
    private final RowSource source;
    private final Field[] fields;
    private final IRubyObject[] keys; // hash keys (String or Symbol), null when asArray
    private final int count;
    private final boolean asArray;
    private final boolean symbolizeKeys;
    private final boolean cast;
    private final CastCtx ctx;

    public RowReader(Ruby runtime,
                     RowSource source,
                     boolean asArray,
                     boolean cast,
                     boolean castBool,
                     boolean symbolizeKeys,
                     String dbTimezone,
                     String appTimezone,
                     RubyClass errorClass,
                     IRubyObject localOffset) {
        this.source = source;
        this.asArray = asArray;
        this.symbolizeKeys = symbolizeKeys;
        this.cast = cast;
        this.ctx = new CastCtx(runtime, castBool, dbTimezone, appTimezone, errorClass, localOffset);

        this.fields = source.fields();
        this.count = fields.length;
        this.keys = asArray ? null : new IRubyObject[count];
        for (int i = 0; i < count; i++) {
            if (keys != null) {
                keys[i] = symbolizeKeys ? runtime.newSymbol(fields[i].name) : runtime.newString(fields[i].name);
            }
        }
    }

    /** Next row as a RubyHash/RubyArray, or nil at end of result. */
    public IRubyObject nextRow(final Ruby runtime) {
        if (!source.next()) return runtime.getNil();

        IRubyObject[] vals = new IRubyObject[count];
        for (int i = 0; i < count; i++) {
            vals[i] = cast ? Caster.byteCast(ctx, source, i, fields[i])
                           : Caster.raw(ctx, source, i, fields[i]);
        }

        if (asArray) return RubyArray.newArray(runtime, vals);

        RubyHash hash = RubyHash.newHash(runtime);
        for (int i = 0; i < count; i++) hash.fastASet(keys[i], vals[i]);
        return hash;
    }

    void close() {
        source.close();
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

    boolean isAsArray() {
        return asArray;
    }

    boolean isSymbolizeKeys() {
        return symbolizeKeys;
    }

    /** Field name as a Symbol or String, for rebuilding rows under per-call options. */
    IRubyObject key(Ruby runtime, int i, boolean symbolize) {
        return symbolize ? runtime.newSymbol(fields[i].name) : runtime.newString(fields[i].name);
    }
}
