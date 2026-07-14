package mysql2;

import java.util.ArrayList;

import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyClass;
import org.jruby.RubyHash;
import org.jruby.RubyInteger;
import org.jruby.RubyObject;
import org.jruby.anno.JRubyMethod;
import org.jruby.runtime.Block;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

/** Mysql2::Result -- native, Enumerable over a JDBC ResultSet via RowReader. */
public class Result extends RubyObject {
    // NOTE: results are only created from Client#query
    public static final ObjectAllocator ALLOCATOR = (runtime, klass) -> {
        throw runtime.newTypeError("wrapping without a result set is not supported");
    };

    private final RowReader reader;
    private final boolean fromStatement; // prepared-statement result (affects each without a block)
    private IRubyObject[] cachedRows;
    private boolean closed;

    private int streamedCount;         // rows yielded/drained so far (streaming)
    private boolean streamingComplete; // streaming result fully consumed

    public Result(Ruby runtime, RubyClass klass, RowReader reader, boolean cacheRows, boolean fromStatement) {
        super(runtime, klass);

        this.reader = reader;
        this.fromStatement = fromStatement;

        // cache_rows (default): buffer all rows now and free the result set, like mysql2's store_result
        // this lets a prepared statement be re-executed without invalidating earlier results
        if (cacheRows) {
            ArrayList<IRubyObject> rows = new ArrayList<>(reader.count);
            IRubyObject row;
            while (!(row = reader.nextRow(runtime)).isNil()) rows.add(row);
            this.cachedRows = rows.toArray(new IRubyObject[rows.size()]);
            close();
        }
    }

    @JRubyMethod(optional = 1)
    public IRubyObject each(ThreadContext context, IRubyObject[] args, Block block) {
        final Ruby runtime = context.runtime;

        // per-call as: / symbolize_keys: override the query-time row format
        boolean asArray = reader.asArray;
        boolean symbolizeKeys = reader.symbolizeKeys;
        if (args.length > 0 && args[0] instanceof RubyHash) {
            final RubyHash hash = (RubyHash) args[0];
            IRubyObject as = hash.op_aref(context, runtime.newSymbol("as"));
            if (!as.isNil()) asArray = as.asJavaString().equals("array");
            IRubyObject sym_keys = hash.op_aref(context, runtime.newSymbol("symbolize_keys"));
            if (!sym_keys.isNil()) symbolizeKeys = sym_keys.isTrue();
        }
        boolean transform = asArray != reader.asArray || symbolizeKeys != reader.symbolizeKeys;

        // cached (cache_rows, default): rows were buffered at construction
        // a query result without a block returns the row array (mysql2 returns wrapper->rows),
        // so `result.each` / `result.each(as: :array)` don't raise;
        // a prepared-statement result instead yields unconditionally
        if (cachedRows != null) {
            if (!block.isGiven() && !fromStatement) {
                IRubyObject[] rows = new IRubyObject[cachedRows.length];
                for (int i = 0; i < cachedRows.length; i++) {
                    rows[i] = transform ? transformRow(context, cachedRows[i], asArray, symbolizeKeys) : cachedRows[i];
                }
                return RubyArray.newArrayNoCopy(runtime, rows);
            }

            for (int i = 0; i < cachedRows.length; i++) {
                block.yield(context, transform ? transformRow(context, cachedRows[i], asArray, symbolizeKeys) : cachedRows[i]);
            }
            return this;
        }

        // streaming (cache_rows: false): drain lazily - rows are always fetched (#count tracks progress)
        // but only yielded when a block is given; the result set is closed only on full exhaustion, so a
        // partial consume (e.g. #first) can be resumed by a later #each
        if (streamingComplete) {
            throw Client.error(context, "You have already fetched all the rows for this query "
                    + "and streaming is true. (to reiterate you must requery).");
        }
        IRubyObject row;
        while (!(row = reader.nextRow(runtime)).isNil()) {
            streamedCount++;
            if (block.isGiven()) {
                block.yield(context, transform ? transformRow(context, row, asArray, symbolizeKeys) : row);
            }
        }
        streamingComplete = true;
        close();

        return runtime.newEmptyArray();
    }

    /** Convert a buffered row to the per-call as: / symbolize_keys: format. */
    private IRubyObject transformRow(ThreadContext context,
                                     final IRubyObject row,
                                     final boolean asArray,
                                     final boolean symbolizeKeys) {
        final Ruby runtime = context.runtime;
        if (row instanceof RubyArray) {
            RubyArray<?> ary = (RubyArray<?>) row;
            if (asArray) return ary;

            RubyHash hash = RubyHash.newHash(runtime);
            for (int i = 0; i < ary.size(); i++) hash.fastASet(reader.key(runtime, i, symbolizeKeys), ary.eltInternal(i));
            return hash;
        }

        RubyHash rowHash = (RubyHash) row;
        if (asArray) return rowHash.values(context);

        RubyHash hash = RubyHash.newHash(runtime);
        RubyArray<?> hkeys = rowHash.keys();
        for (int i = 0; i < hkeys.size(); i++) {
            hash.fastASet(reader.key(runtime, i, symbolizeKeys), rowHash.op_aref(context, hkeys.eltInternal(i)));
        }
        return hash;
    }

    @JRubyMethod
    public RubyArray fields(ThreadContext context) {
        return RubyArray.newArrayNoCopy(context.runtime, reader.fieldNames(context.runtime));
    }

    @JRubyMethod(name = "field_types")
    public RubyArray field_types(ThreadContext context) {
        return RubyArray.newArrayNoCopy(context.runtime, reader.fieldTypes(context.runtime));
    }

    @JRubyMethod(name = {"count", "size"})
    public RubyInteger count(ThreadContext context) {
        // cached: total buffered rows
        // streaming: rows drained so far (does not advance the stream)
        return context.runtime.newFixnum(cachedRows != null ? cachedRows.length : streamedCount);
    }

    @JRubyMethod
    public IRubyObject free(ThreadContext context) {
        close();
        return context.nil;
    }

    @JRubyMethod(name = "server_flags")
    public IRubyObject server_flags(ThreadContext context) {
        return context.nil;
    }

    private RuntimeException streamingError(Ruby runtime, String message) {
        RubyClass error = runtime.getModule("Mysql2").getClass("Error");
        return runtime.newRaiseException(error, message);
    }

    private void close() {
        if (closed) return;
        closed = true;
        reader.close();
    }
}
