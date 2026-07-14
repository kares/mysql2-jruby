package mysql2;

import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyHash;
import org.jruby.RubyString;
import org.jruby.runtime.builtin.IRubyObject;

/**
 * Materializes rows of a text-protocol result into Ruby objects.
 * Rows and column metadata come from a {@link RowSource}.
 * One instance per Mysql2::Result; Ruby drives it via nextRow() until it returns nil
 */
final class RowReader {
    private final RowSource source;
    final int count;

    final boolean asArray;
    private final IRubyObject[] keys; // hash keys (String or Symbol), null when asArray
    final boolean symbolizeKeys;

    private final boolean cast;
    private final CastCtx ctx;

    public RowReader(Ruby runtime,
                     RowSource source,
                     boolean asArray,
                     boolean cast,
                     boolean castBool,
                     boolean symbolizeKeys,
                     String dbTimezone,
                     String appTimezone) {
        this.source = source;
        this.asArray = asArray;
        this.symbolizeKeys = symbolizeKeys;
        this.cast = cast;
        this.ctx = new CastCtx(runtime, castBool, dbTimezone, appTimezone);

        this.count = source.size();
        if (asArray) {
            this.keys = null;
        } else {
            this.keys = new IRubyObject[count];
            for (int i = 0; i < count; i++) {
                keys[i] = key(runtime, i, symbolizeKeys);
            }
        }
    }

    /** Next row as a RubyHash/RubyArray, or nil at end of result */
    public IRubyObject nextRow(final Ruby runtime) {
        if (!source.next()) return runtime.getNil();

        IRubyObject[] vals = new IRubyObject[count];
        for (int i = 0; i < count; i++) {
            vals[i] = cast ? Caster.byteCast(ctx, source, i, source.getField(i))
                           : Caster.raw(ctx, source, i, source.getField(i));
        }

        if (asArray) return RubyArray.newArrayNoCopy(runtime, vals);

        RubyHash hash = RubyHash.newHash(runtime);
        for (int i = 0; i < count; i++) hash.fastASet(keys[i], vals[i]);
        return hash;
    }

    /** Rewind to before the first row, so a non-streaming result can be re-iterated */
    public void reset() {
        source.reset();
    }

    public void close() {
        source.close();
    }

    /** Field names (Mysql2::Result#fields), always as frozen strings */
    IRubyObject[] fieldNames(final Ruby runtime) {
        IRubyObject[] names = new IRubyObject[count];
        for (int i = 0; i < count; i++) {
            // names[i] = runtime.freezeAndDedupString(runtime.newString(fields[i].name));
            final RubyString fieldName = runtime.newString(source.fieldName(i));
            fieldName.setFrozen(true);
            names[i] = fieldName;
        }
        return names;
    }

    /** MySQL type descriptor strings (Mysql2::Result#field_types) */
    IRubyObject[] fieldTypes(final Ruby runtime) {
        IRubyObject[] types = new IRubyObject[count];
        for (int i = 0; i < count; i++) types[i] = runtime.newString(source.typeName(i));
        return types;
    }

    IRubyObject key(Ruby runtime, int i, boolean symbolize) {
        final String fieldName = source.fieldName(i);
        return symbolize ? runtime.newSymbol(fieldName) : runtime.newString(fieldName);
    }
}
