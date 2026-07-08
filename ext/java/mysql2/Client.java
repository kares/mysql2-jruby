package mysql2;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.jruby.RubyException;
import org.jruby.RubyHash;
import org.jruby.RubyObject;
import org.jruby.RubyString;
import org.jruby.anno.JRubyMethod;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;

/** Mysql2::Client backed by a java.sql.Connection (Connector/J). */
public class Client extends RubyObject {
    public static final ObjectAllocator ALLOCATOR = Client::new;

    private Connection connection;
    private RubyHash queryOptions;
    private long affectedRows;
    private long lastId;

    public Client(Ruby runtime, RubyClass klass) {
        super(runtime, klass);
    }

    // --- lifecycle -----------------------------------------------------------

    @JRubyMethod(name = "initialize", optional = 1, visibility = Visibility.PRIVATE)
    public IRubyObject initialize(ThreadContext context, IRubyObject[] args) {
        Ruby runtime = context.runtime;
        IRubyObject optsArg = args.length > 0 ? args[0] : context.nil;
        if (!optsArg.isNil() && !(optsArg instanceof RubyHash)) {
            throw error(context, "Options parameter must be a Hash");
        }
        RubyHash opts = optsArg.isNil() ? RubyHash.newHash(runtime) : (RubyHash) optsArg;

        this.queryOptions = (RubyHash) defaultQueryOptions(context, getMetaClass()).dup(context);
        this.queryOptions.callMethod(context, "merge!", opts);

        String user = str(opt(context, opts, "username", "user"));
        String pass = str(opt(context, opts, "password", "pass"));
        String host = orDefault(str(opt(context, opts, "host", "hostname")), "localhost");
        IRubyObject portObj = opt(context, opts, "port", null);
        int port = portObj.isNil() ? 3306 : (int) portObj.convertToInteger().getLongValue();
        String database = orDefault(str(opt(context, opts, "database", "dbname", "db")), "");

        String encoding = orDefault(str(opt(context, opts, "encoding")), "utf8mb4");

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
        try {
            this.connection = DriverManager.getConnection(url, user, pass);
            // Make result strings come back in the client encoding (like mysql2's
            // character_set_results), so field metadata reports that charset and
            // getBytes() yields bytes we can tag correctly.
            try (Statement s = connection.createStatement()) {
                s.execute("SET character_set_results = " + sanitizeCharset(encoding));
            }
        } catch (SQLException e) {
            throw errorFromSQL(context, e);
        }
        return context.nil;
    }

    @JRubyMethod
    public IRubyObject close(ThreadContext context) {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException e) {
            throw errorFromSQL(context, e);
        }
        return context.nil;
    }

    @JRubyMethod(name = "closed?")
    public IRubyObject closed_p(ThreadContext context) {
        try {
            return context.runtime.newBoolean(connection == null || connection.isClosed());
        } catch (SQLException e) {
            return context.tru;
        }
    }

    @JRubyMethod
    public IRubyObject ping(ThreadContext context) {
        try {
            return context.runtime.newBoolean(connection != null && !connection.isClosed() && connection.isValid(0));
        } catch (SQLException e) {
            return context.fals;
        }
    }

    // --- query ---------------------------------------------------------------

    @JRubyMethod(required = 1, optional = 1)
    public IRubyObject query(ThreadContext context, IRubyObject[] args) {
        Ruby runtime = context.runtime;
        String sql = args[0].convertToString().asJavaString();
        RubyHash merged = (args.length > 1 && !args[1].isNil())
                ? (RubyHash) queryOptions.callMethod(context, "merge", args[1])
                : queryOptions;

        Statement stmt = null;
        try {
            stmt = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            boolean hasResult = stmt.execute(sql, Statement.RETURN_GENERATED_KEYS);
            if (hasResult) {
                return buildResult(context, stmt.getResultSet(), stmt, merged);
            } else {
                this.affectedRows = Math.max(stmt.getUpdateCount(), 0);
                ResultSet keys = stmt.getGeneratedKeys();
                this.lastId = keys.next() ? keys.getLong(1) : 0;
                stmt.close();
                return context.nil;
            }
        } catch (SQLException e) {
            try { if (stmt != null) stmt.close(); } catch (SQLException ignore) { }
            throw errorFromSQL(context, e);
        }
    }

    @JRubyMethod(name = "prepare")
    public mysql2.Statement prepare(ThreadContext context, IRubyObject sql) {
        try {
            final String sqlString = sql.convertToString().asJavaString();
            PreparedStatement ps = connection.prepareStatement(sqlString, Statement.RETURN_GENERATED_KEYS);
            RubyClass Statement = context.runtime.getModule("Mysql2").getClass("Statement");
            return new mysql2.Statement(context.runtime, Statement, this, ps);
        } catch (SQLException e) {
            throw errorFromSQL(context, e);
        }
    }

    /** Build a Mysql2::Result over rs. stmtToClose is closed with the result set
     *  (pass null to leave the statement open, e.g. a reusable PreparedStatement). */
    Result buildResult(ThreadContext context, ResultSet resultSet, Statement stmtToClose, RubyHash merged) throws SQLException {
        Ruby runtime = context.runtime;
        RowReader reader = new RowReader(
                runtime, resultSet,
                "array".equals(str(hash(context, merged, "as"))),
                truthy(hash(context, merged, "cast"), true),
                truthy(hash(context, merged, "cast_booleans"), false),
                truthy(hash(context, merged, "symbolize_keys"), false),
                orDefault(str(hash(context, merged, "database_timezone")), "local"),
                str(hash(context, merged, "application_timezone")),
                errorClass(runtime),
                localOffset(context, getMetaClass()));
        boolean cacheRows = truthy(hash(context, merged, "cache_rows"), true);
        RubyClass resultClass = runtime.getModule("Mysql2").getClass("Result");
        return new Result(runtime, resultClass, resultSet, stmtToClose, reader, cacheRows);
    }

    RubyHash mergedOptions(ThreadContext context, IRubyObject opts) {
        return (opts != null && !opts.isNil())
                ? (RubyHash) queryOptions.callMethod(context, "merge", opts)
                : queryOptions;
    }

    @JRubyMethod(name = "escape_string", alias = "escape")
    public IRubyObject escape_string(ThreadContext context, IRubyObject str) {
        return escapeString(context.runtime, str.convertToString());
    }

    @JRubyMethod(meta = true, name = "escape")
    public static IRubyObject escape(ThreadContext context, IRubyObject self, IRubyObject str) {
        return escapeString(context.runtime, str.convertToString());
    }

    // --- accessors -----------------------------------------------------------

    @JRubyMethod(name = "query_options")
    public IRubyObject query_options(ThreadContext context) {
        return queryOptions;
    }

    @JRubyMethod(name = "affected_rows")
    public IRubyObject affected_rows(ThreadContext context) {
        return context.runtime.newFixnum(affectedRows);
    }

    @JRubyMethod(name = "last_id")
    public IRubyObject last_id(ThreadContext context) {
        return context.runtime.newFixnum(lastId);
    }

    @JRubyMethod(name = "read_timeout")
    public IRubyObject read_timeout(ThreadContext context) {
        return context.nil;
    }

    @JRubyMethod(name = "server_info")
    public IRubyObject server_info(ThreadContext context) {
        final Ruby runtime = context.runtime;
        try {
            RubyHash info = RubyHash.newHash(runtime);
            info.fastASet(runtime.newSymbol("version"), runtime.newString(connection.getMetaData().getDatabaseProductVersion()));
            return info;
        } catch (SQLException e) {
            throw errorFromSQL(context, e);
        }
    }

    // Client library info. The "client" here is the JDBC driver (Connector/J),
    // so version data comes from its metadata; values are US-ASCII like mysql2's
    // libmysql-derived strings.
    @JRubyMethod(name = "info")
    public IRubyObject info(ThreadContext context) {
        try {
            java.sql.DatabaseMetaData md = connection.getMetaData();
            return infoHash(context.runtime, md.getDriverMajorVersion(),
                    md.getDriverMinorVersion(), md.getDriverVersion());
        } catch (SQLException e) {
            throw errorFromSQL(context, e);
        }
    }

    @JRubyMethod(meta = true, name = "info")
    public static IRubyObject info_s(ThreadContext context, IRubyObject self) {
        try {
            java.sql.Driver d = new com.mysql.cj.jdbc.Driver();
            String raw = com.mysql.cj.jdbc.Driver.class.getPackage().getImplementationVersion();
            return infoHash(context.runtime, d.getMajorVersion(), d.getMinorVersion(), raw);
        } catch (SQLException e) {
            throw context.runtime.newRuntimeError(e.getMessage());
        }
    }

    private static IRubyObject infoHash(Ruby runtime, int major, int minor, String raw) {
        int patch = parsePatch(raw, major, minor);
        RubyHash hash = RubyHash.newHash(runtime);
        hash.fastASet(runtime.newSymbol("id"), runtime.newFixnum(major * 10000L + minor * 100L + patch));
        hash.fastASet(runtime.newSymbol("version"), usAscii(runtime, major + "." + minor + "." + patch));
        hash.fastASet(runtime.newSymbol("header_version"), usAscii(runtime, raw == null ? "" : raw));
        return hash;
    }

    /** Extract the patch component from a driver version string like "9.4.0". */
    private static int parsePatch(String raw, int major, int minor) {
        if (raw == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b" + major + "\\." + minor + "\\.(\\d+)").matcher(raw);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static RubyString usAscii(Ruby runtime, String s) {
        return runtime.newString(new ByteList(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                org.jcodings.specific.USASCIIEncoding.INSTANCE, false));
    }

    @JRubyMethod(meta = true, name = "default_query_options")
    public static RubyHash defaultQueryOptions(ThreadContext context, IRubyObject self) {
        final Ruby runtime = context.runtime;
        RubyHash hash = RubyHash.newHash(runtime);
        hash.fastASet(runtime.newSymbol("as"), runtime.newSymbol("hash"));
        hash.fastASet(runtime.newSymbol("async"), runtime.getFalse());
        hash.fastASet(runtime.newSymbol("cast_booleans"), runtime.getFalse());
        hash.fastASet(runtime.newSymbol("symbolize_keys"), runtime.getFalse());
        hash.fastASet(runtime.newSymbol("database_timezone"), runtime.newSymbol("local"));
        hash.fastASet(runtime.newSymbol("application_timezone"), runtime.getNil());
        hash.fastASet(runtime.newSymbol("cache_rows"), runtime.getTrue());
        hash.fastASet(runtime.newSymbol("cast"), runtime.getTrue());
        return hash;
    }

    /** Local timezone offset as a day fraction (Rational); matches mysql2. */
    @JRubyMethod(meta = true, name = "local_offset")
    public static IRubyObject localOffset(ThreadContext context, IRubyObject self) {
        Ruby runtime = context.runtime;
        IRubyObject time = runtime.getTime().callMethod(context, "local", runtime.newFixnum(2010));
        IRubyObject offset = time.callMethod(context, "utc_offset").callMethod(context, "to_r");
        return offset.callMethod(context, "/", runtime.newFixnum(86400));
    }

    // --- helpers -------------------------------------------------------------

    private static RubyString escapeString(Ruby runtime, RubyString str) {
        ByteList in = str.getByteList();
        ByteList out = new ByteList(in.length() + 8);
        out.setEncoding(in.getEncoding());
        for (int i = 0; i < in.length(); i++) {
            byte b = (byte) in.get(i);
            switch (b) {
                case 0:    out.append('\\'); out.append('0'); break;
                case '\n': out.append('\\'); out.append('n'); break;
                case '\r': out.append('\\'); out.append('r'); break;
                case 0x1a: out.append('\\'); out.append('Z'); break;
                case '\\': case '\'': case '"':
                    out.append('\\'); out.append(b); break;
                default:
                    out.append(b);
            }
        }
        return runtime.newString(out);
    }

    RubyClass errorClass(Ruby runtime) {
        return runtime.getModule("Mysql2").getClass("Error");
    }

    RaiseException error(ThreadContext context, String msg) {
        return context.runtime.newRaiseException(errorClass(context.runtime), msg);
    }

    RuntimeException errorFromSQL(ThreadContext context, SQLException e) {
        Ruby runtime = context.runtime;
        IRubyObject errno = runtime.newFixnum(e.getErrorCode());
        IRubyObject sqlState = e.getSQLState() == null ? runtime.getNil() : runtime.newString(e.getSQLState());
        RubyException exc = (RubyException) errorClass(runtime).callMethod(context, "new_with_args",
                new IRubyObject[]{runtime.newString(e.getMessage() == null ? "" : e.getMessage()),
                        runtime.getNil(), errno, sqlState});
        return exc.toThrowable();
    }

    /** Option value by any of the given symbol/string keys. */
    static IRubyObject opt(ThreadContext context, RubyHash opts, String... names) {
        Ruby runtime = context.runtime;
        for (String name : names) {
            if (name == null) continue;
            IRubyObject v = opts.op_aref(context, runtime.newSymbol(name));
            if (!v.isNil()) return v;
            v = opts.op_aref(context, runtime.newString(name));
            if (!v.isNil()) return v;
        }
        return context.nil;
    }

    static IRubyObject hash(ThreadContext context, RubyHash h, String symKey) {
        return h.op_aref(context, context.runtime.newSymbol(symKey));
    }

    static String str(IRubyObject v) {
        return (v == null || v.isNil()) ? null : v.asJavaString();
    }

    /** Whitelist a charset name for safe interpolation into SET character_set_results. */
    private static String sanitizeCharset(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]+")) return "utf8mb4";
        return name;
    }

    static String orDefault(String v, String dflt) {
        return v == null ? dflt : v;
    }

    static boolean truthy(IRubyObject v, boolean dflt) {
        return (v == null || v.isNil()) ? dflt : v.isTrue();
    }
}
