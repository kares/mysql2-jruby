package mysql2;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;

import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyClass;
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

    private ResultSet resultSet;
    private Statement statement;
    private RowReader reader;
    private IRubyObject[] cachedRows;
    private boolean closed;

    public Result(Ruby runtime, RubyClass klass, ResultSet resultSet, Statement statement, RowReader reader, boolean cacheRows) {
        super(runtime, klass);

        this.resultSet = resultSet;
        this.statement = statement;
        this.reader = reader;

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

    @JRubyMethod
    public IRubyObject each(ThreadContext context, Block block) {
        // Like mysql2: rows are yielded, not required. With no block a non-empty
        // result raises LocalJumpError on the first yield, an empty one returns
        // cleanly (so `result.each.to_a` works for empty results).
        if (cachedRows != null) {
            for (int i = 0; i < cachedRows.length; i++) block.yield(context, cachedRows[i]);
            return this;
        }

        final Ruby runtime = context.runtime;
        // streaming (cache_rows: false): read lazily from the live result set.
        try {
            IRubyObject row;
            while (!(row = reader.nextRow(runtime)).isNil()) {
                block.yield(context, row);
            }
        } finally {
            close();
        }
        return this;
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
        RubyArray<?> all = (RubyArray<?>) callMethod(context, "to_a");
        return context.runtime.newFixnum(all.size());
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

    private void close() {
        if (closed) return;
        closed = true;
        try { if (resultSet != null) resultSet.close(); } catch (SQLException ignore) { }
        try { if (statement != null) statement.close(); } catch (SQLException ignore) { }
    }
}
