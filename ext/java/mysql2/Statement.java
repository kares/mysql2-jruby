package mysql2;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import org.jruby.Ruby;
import org.jruby.RubyBignum;
import org.jruby.RubyBoolean;
import org.jruby.RubyClass;
import org.jruby.RubyFloat;
import org.jruby.RubyHash;
import org.jruby.RubyInteger;
import org.jruby.RubyObject;
import org.jruby.anno.JRubyMethod;
import org.jruby.ext.bigdecimal.RubyBigDecimal;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

/**
 * Mysql2::Statement -- native, backed by a JDBC PreparedStatement (client-side
 * param substitution, so results are text protocol and reuse the byte caster).
 */
public class Statement extends RubyObject {
    public static final ObjectAllocator ALLOCATOR = (runtime, klass) -> {
        throw runtime.newTypeError("Mysql2::Statement cannot be allocated; use Client#prepare");
    };

    private final Client client;
    private final PreparedStatement statement;
    private final int paramCount;
    private long affectedRows;
    private long lastId;

    private volatile boolean closed;

    Statement(Ruby runtime, RubyClass klass, Client client, PreparedStatement statement) throws SQLException {
        super(runtime, klass);
        this.client = client;
        this.statement = statement;
        this.paramCount = statement.getParameterMetaData().getParameterCount();
    }

    @JRubyMethod(name = "param_count")
    public IRubyObject param_count(ThreadContext context) {
        return context.runtime.newFixnum(paramCount);
    }

    @JRubyMethod(name = "field_count")
    public IRubyObject field_count(ThreadContext context) {
        try {
            ResultSetMetaData md = statement.getMetaData();
            return context.runtime.newFixnum(md == null ? 0 : md.getColumnCount());
        } catch (SQLException e) {
            throw client.errorFromSQL(context, e);
        }
    }

    @JRubyMethod(name = {"execute", "_execute"}, rest = true)
    public IRubyObject execute(ThreadContext context, IRubyObject[] args) {
        if (closed) {
            throw client.error(context, "Invalid statement handle");
        }

        IRubyObject opts = null;
        int argCount = args.length;
        if (argCount > 0 && args[argCount - 1] instanceof RubyHash) {
            opts = args[argCount - 1]; argCount--;
        }
        if (argCount != paramCount) {
            throw client.error(context, "Bind parameter count (" + paramCount + ") doesn't match number of arguments (" + argCount + ")");
        }

        try {
            for (int i = 0; i < argCount; i++) bindParam(context, i + 1, args[i]);
            boolean hasResult = statement.execute();
            if (hasResult) { // stmtToClose == null: keep the PreparedStatement open for re-execute
                return client.buildResult(context, statement.getResultSet(), null, client.mergedOptions(context, opts));
            }
            this.affectedRows = Math.max(statement.getUpdateCount(), 0);
            final ResultSet keys = statement.getGeneratedKeys();
            this.lastId = keys.next() ? keys.getLong(1) : 0;

            return context.nil;
        } catch (SQLException e) {
            throw client.errorFromSQL(context, e);
        }
    }

    @JRubyMethod
    public IRubyObject fields(ThreadContext context) {
        Ruby runtime = context.runtime;
        try {
            ResultSetMetaData md = statement.getMetaData();
            if (md == null) return context.nil;
            IRubyObject[] names = new IRubyObject[md.getColumnCount()];
            for (int i = 0; i < names.length; i++) names[i] = runtime.newString(md.getColumnLabel(i + 1));
            return runtime.newArray(names);
        } catch (SQLException e) {
            throw client.errorFromSQL(context, e);
        }
    }

    @JRubyMethod(name = "last_id")
    public IRubyObject last_id(ThreadContext context) {
        return context.runtime.newFixnum(lastId);
    }

    @JRubyMethod(name = "affected_rows")
    public IRubyObject affected_rows(ThreadContext context) {
        return context.runtime.newFixnum(affectedRows);
    }

    @JRubyMethod
    public IRubyObject close(ThreadContext context) {
        try {
            if (!closed) {
                statement.close();
                closed = true;
            }
        } catch (SQLException e) {
            throw client.errorFromSQL(context, e);
        }
        return context.nil;
    }

    @JRubyMethod(name = "closed?")
    public IRubyObject closed_p(ThreadContext context) {
        return context.runtime.newBoolean(closed);
    }

    // --- parameter binding ---------------------------------------------------

    private void bindParam(ThreadContext context, int idx, IRubyObject param) throws SQLException {
        if (param.isNil()) {
            statement.setObject(idx, null);
        } else if (param instanceof RubyBoolean) {
            statement.setBoolean(idx, param.isTrue());
        } else if (param instanceof RubyBignum) {
            statement.setBigDecimal(idx, new BigDecimal(((RubyBignum) param).getBigIntegerValue()));
        } else if (param instanceof RubyInteger) {
            statement.setLong(idx, ((RubyInteger) param).getLongValue());
        } else if (param instanceof RubyFloat) {
            statement.setDouble(idx, ((RubyFloat) param).getDoubleValue());
        } else if (param instanceof RubyBigDecimal) {
            statement.setBigDecimal(idx, ((RubyBigDecimal) param).getValue());
        } else if (isTemporal(param)) {
            statement.setString(idx, temporalToString(context, param));
        } else {
            statement.setString(idx, param.asString().asJavaString());
        }
    }

    private static boolean isTemporal(IRubyObject p) {
        String name = p.getMetaClass().getName();
        return "Time".equals(name) || "DateTime".equals(name) || "Date".equals(name);
    }

    private static String temporalToString(ThreadContext context, IRubyObject p) {
        String fmt = "Date".equals(p.getMetaClass().getName()) ? "%Y-%m-%d" : "%Y-%m-%d %H:%M:%S.%6N";
        return p.callMethod(context, "strftime", context.runtime.newString(fmt)).asString().asJavaString();
    }
}
