package mysql2;

import java.util.ArrayList;

import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyClass;
import org.jruby.RubyHash;
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

    private RowReader reader;
    private IRubyObject[] cachedRows;
    private boolean closed;
    private int streamedCount;         // rows yielded/drained so far (streaming)
    private boolean streamingComplete; // streaming result fully consumed
    private final boolean fromStatement; // prepared-statement result (affects each without a block)

    public Result(Ruby runtime, RubyClass klass, RowReader reader, boolean cacheRows, boolean fromStatement) {
        super(runtime, klass);

        this.reader = reader;
        this.fromStatement = fromStatement;

        // cache_rows (default): buffer all rows now and free the result set, like mysql2's store_result
        // this lets a prepared statement be re-executed without invalidating earlier results
        if (cacheRows) {
            ArrayList<IRubyObject> rows = new ArrayList<>();
            IRubyObject row;
            while (!(row = reader.nextRow(runtime)).isNil()) rows.add(row);
            this.cachedRows = rows.toArray(new IRubyObject[rows.size()]);
            close();
        }
    }

    @JRubyMethod(optional = 1)
    public IRubyObject each(ThreadContext context, IRubyObject[] args, Block block) {
        final Ruby runtime = context.runtime;

        // Per-call as:/symbolize_keys: override the query-time row format.
        boolean targetArray = reader.isAsArray();
        boolean targetSym = reader.isSymbolizeKeys();
        if (args.length > 0 && args[0] instanceof RubyHash) {
            RubyHash o = (RubyHash) args[0];
            IRubyObject as = o.op_aref(context, runtime.newSymbol("as"));
            if (!as.isNil()) targetArray = as.equals(runtime.newSymbol("array"));
            IRubyObject sk = o.op_aref(context, runtime.newSymbol("symbolize_keys"));
            if (!sk.isNil()) targetSym = sk.isTrue();
        }
        boolean transform = targetArray != reader.isAsArray() || targetSym != reader.isSymbolizeKeys();

        // Cached (cache_rows, default): rows were buffered at construction.
        // A query result without a block returns the row array (mysql2 returns
        // wrapper->rows), so `result.each` / `result.each(as: :array)` don't raise.
        // A prepared-statement result instead yields unconditionally, so `each`
        // with no block raises LocalJumpError on a non-empty result (statement_spec).
        if (cachedRows != null) {
            if (!block.isGiven() && !fromStatement) {
                IRubyObject[] out = new IRubyObject[cachedRows.length];
                for (int i = 0; i < cachedRows.length; i++) {
                    out[i] = transform ? transformRow(context, cachedRows[i], targetArray, targetSym) : cachedRows[i];
                }
                return RubyArray.newArray(runtime, out);
            }
            for (int i = 0; i < cachedRows.length; i++) {
                block.yield(context, transform ? transformRow(context, cachedRows[i], targetArray, targetSym) : cachedRows[i]);
            }
            return this;
        }

        // Streaming (cache_rows: false): drain lazily. Rows are always fetched
        // (so #count tracks progress) but only yielded when a block is given.
        // The result set is closed only on full exhaustion, so a partial consume
        // (e.g. #first) can be resumed by a later #each.
        if (streamingComplete) {
            throw streamingError(runtime, "You have already fetched all the rows for this query "
                    + "and streaming is true. (to reiterate you must requery).");
        }
        IRubyObject row;
        while (!(row = reader.nextRow(runtime)).isNil()) {
            streamedCount++;
            if (block.isGiven()) {
                block.yield(context, transform ? transformRow(context, row, targetArray, targetSym) : row);
            }
        }
        streamingComplete = true;
        close();
        return runtime.newEmptyArray();
    }

    /** Convert a buffered row to the per-call as:/symbolize_keys format. */
    private IRubyObject transformRow(ThreadContext context, IRubyObject row, boolean targetArray, boolean targetSym) {
        final Ruby runtime = context.runtime;
        if (row instanceof RubyArray) {
            RubyArray<?> arr = (RubyArray<?>) row;
            if (targetArray) return arr;
            RubyHash h = RubyHash.newHash(runtime);
            for (int i = 0; i < arr.size(); i++) h.fastASet(reader.key(runtime, i, targetSym), arr.eltInternal(i));
            return h;
        }
        RubyHash hash = (RubyHash) row;
        if (targetArray) return hash.callMethod(context, "values");
        RubyHash h = RubyHash.newHash(runtime);
        RubyArray<?> hkeys = hash.keys();
        for (int i = 0; i < hkeys.size(); i++) {
            h.fastASet(reader.key(runtime, i, targetSym), hash.op_aref(context, hkeys.eltInternal(i)));
        }
        return h;
    }

    @JRubyMethod
    public RubyArray fields(ThreadContext context) {
        return RubyArray.newArray(context.runtime, reader.fieldNames(context.runtime));
    }

    @JRubyMethod(name = "field_types")
    public RubyArray field_types(ThreadContext context) {
        return RubyArray.newArray(context.runtime, reader.fieldTypes(context.runtime));
    }

    @JRubyMethod(name = {"count", "size"})
    public IRubyObject count(ThreadContext context) {
        // Cached: total buffered rows. Streaming: rows drained so far (does not
        // advance the stream), matching mysql2's running count.
        int n = cachedRows != null ? cachedRows.length : streamedCount;
        return context.runtime.newFixnum(n);
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
        RubyClass error = (RubyClass) runtime.getModule("Mysql2").getClass("Error");
        return runtime.newRaiseException(error, message);
    }

    private void close() {
        if (closed) return;
        closed = true;
        reader.close();
    }
}
