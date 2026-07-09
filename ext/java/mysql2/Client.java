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
    private String queryInfoString; // mysql_info() of the last query, or null
    private long warningCount;      // server warnings from the last query
    private org.jcodings.Encoding clientEncoding = org.jcodings.specific.UTF8Encoding.INSTANCE;

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
        this.clientEncoding = resolveEncoding(runtime, encoding);

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
            this.queryInfoString = extractServerInfo(stmt);
            this.warningCount = countWarnings(stmt);
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

    @JRubyMethod(name = "warning_count")
    public IRubyObject warning_count(ThreadContext context) {
        return context.runtime.newFixnum(warningCount);
    }

    // mysql_set_server_option: true for the known OPTION_MULTI_STATEMENTS_ON/OFF
    // values, false otherwise. The server-side toggle is a no-op here since
    // multi-statement query support (next_result/store_result) is not implemented.
    @JRubyMethod(name = "set_server_option")
    public IRubyObject set_server_option(ThreadContext context, IRubyObject value) {
        long v = value.convertToInteger().getLongValue();
        boolean known = v == 0 /* OPTION_MULTI_STATEMENTS_ON */ || v == 1 /* OPTION_MULTI_STATEMENTS_OFF */;
        return context.runtime.newBoolean(known);
    }

    // In mysql2 this prevents a forked child's GC from closing the parent's shared
    // MySQL socket. JRuby has no fork and there is no JDBC analog, so the getter
    // reports nil (unsupported) and the setter is a no-op; #close always closes.
    @JRubyMethod(name = "automatic_close?")
    public IRubyObject automatic_close_p(ThreadContext context) {
        return context.nil;
    }

    @JRubyMethod(name = "automatic_close=")
    public IRubyObject set_automatic_close(ThreadContext context, IRubyObject value) {
        return value;
    }

    private static long countWarnings(Statement stmt) {
        try {
            long n = 0;
            java.sql.SQLWarning w = stmt.getWarnings();
            while (w != null) { n++; w = w.getNextWarning(); }
            return n;
        } catch (SQLException e) {
            return 0;
        }
    }

    @JRubyMethod(name = "query_info_string")
    public IRubyObject query_info_string(ThreadContext context) {
        return queryInfoString == null ? context.nil : context.runtime.newString(queryInfoString);
    }

    @JRubyMethod(name = "query_info")
    public IRubyObject query_info(ThreadContext context) {
        Ruby runtime = context.runtime;
        RubyHash info = RubyHash.newHash(runtime);
        if (queryInfoString == null) return info;
        // "Records: 2  Duplicates: 0  Warnings: 0" -> {records: 2, duplicates: 0, warnings: 0}
        String[] parts = queryInfoString.trim().split("\\s+");
        for (int i = 0; i + 1 < parts.length; i += 2) {
            String key = parts[i].toLowerCase().replace(":", "");
            long value = 0;
            try { value = Long.parseLong(parts[i + 1]); } catch (NumberFormatException ignore) { }
            info.fastASet(runtime.newSymbol(key), runtime.newFixnum(value));
        }
        return info;
    }

    /** MySQL's mysql_info() for the last query, via Connector/J's internal result
     *  (getResultSetInternal() is public on JdbcStatement; the returned type
     *  extends com.mysql.cj.protocol.Resultset which exposes getServerInfo()). */
    private static String extractServerInfo(Statement stmt) {
        if (!(stmt instanceof com.mysql.cj.jdbc.JdbcStatement)) return null;
        try {
            com.mysql.cj.jdbc.result.ResultSetInternalMethods rs =
                    ((com.mysql.cj.jdbc.JdbcStatement) stmt).getResultSetInternal();
            if (rs == null) return null;
            String info = rs.getServerInfo();
            return (info == null || info.isEmpty()) ? null : info;
        } catch (Exception e) {
            return null;
        }
    }

    @JRubyMethod(name = "database")
    public IRubyObject database(ThreadContext context) {
        try {
            String db = connection.getCatalog();
            return (db == null || db.isEmpty()) ? context.nil : context.runtime.newString(db);
        } catch (SQLException e) {
            throw errorFromSQL(context, e);
        }
    }

    @JRubyMethod(name = "select_db")
    public IRubyObject select_db(ThreadContext context, IRubyObject db) {
        try {
            connection.setCatalog(db.convertToString().asJavaString());
        } catch (SQLException e) {
            throw errorFromSQL(context, e);
        }
        return db;
    }

    @JRubyMethod(name = "server_info")
    public IRubyObject server_info(ThreadContext context) {
        final Ruby runtime = context.runtime;
        try {
            String version = connection.getMetaData().getDatabaseProductVersion();
            RubyHash info = RubyHash.newHash(runtime);
            info.fastASet(runtime.newSymbol("id"), runtime.newFixnum(serverVersionId(version)));
            // tag with the connection encoding, exported to Encoding.default_internal
            info.fastASet(runtime.newSymbol("version"), encodedString(context, version));
            return info;
        } catch (SQLException e) {
            throw errorFromSQL(context, e);
        }
    }

    /** mysql_get_server_version() equivalent: major*10000 + minor*100 + patch. */
    private static long serverVersionId(String version) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(version);
        if (!m.find()) return 0;
        return Long.parseLong(m.group(1)) * 10000 + Long.parseLong(m.group(2)) * 100 + Long.parseLong(m.group(3));
    }

    /** Build a String tagged with the connection encoding, exported to default_internal. */
    private IRubyObject encodedString(ThreadContext context, String s) {
        Ruby runtime = context.runtime;
        RubyString str = runtime.newString(new ByteList(
                s.getBytes(java.nio.charset.StandardCharsets.UTF_8), clientEncoding, false));
        org.jcodings.Encoding di = runtime.getDefaultInternalEncoding();
        if (di != null && di != clientEncoding) {
            return org.jruby.util.io.EncodingUtils.strConvEnc(context, str, clientEncoding, di);
        }
        return str;
    }

    private static org.jcodings.Encoding resolveEncoding(Ruby runtime, String mysqlCharset) {
        String name;
        switch (mysqlCharset.toLowerCase()) {
            case "utf8": case "utf8mb4": case "utf8mb3": name = "UTF-8"; break;
            case "ascii": case "us-ascii": name = "US-ASCII"; break;
            case "binary": name = "ASCII-8BIT"; break;
            case "latin1": name = "Windows-1252"; break;
            default: name = mysqlCharset; break;
        }
        try {
            org.jcodings.Encoding e = runtime.getEncodingService().getEncodingFromString(name);
            if (e != null) return e;
        } catch (RuntimeException ignore) {
            // fall through
        }
        return org.jcodings.specific.UTF8Encoding.INSTANCE;
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

    /** Local timezone offset as a day fraction (Rational); matches mysql2 */
    @JRubyMethod(meta = true, name = "local_offset")
    public static IRubyObject localOffset(ThreadContext context, IRubyObject self) {
        Ruby runtime = context.runtime;
        IRubyObject time = runtime.getTime().callMethod(context, "local", runtime.newFixnum(2010));
        IRubyObject offset = time.callMethod(context, "utc_offset").callMethod(context, "to_r");
        return offset.callMethod(context, "/", runtime.newFixnum(86400));
    }

    /** Define Mysql2::Client connection-flag / SSL / session-track constants */
    static void defineConstants(Ruby runtime, RubyClass klass) {
        // CLIENT_* capability flags (mysql_com.h)
        long LONG_PASSWORD = 1, FOUND_ROWS = 2, LONG_FLAG = 4, CONNECT_WITH_DB = 8,
             NO_SCHEMA = 16, COMPRESS = 32, ODBC = 64, LOCAL_FILES = 128, IGNORE_SPACE = 256,
             PROTOCOL_41 = 512, INTERACTIVE = 1024, SSL = 2048, IGNORE_SIGPIPE = 4096,
             TRANSACTIONS = 8192, RESERVED = 16384, SECURE_CONNECTION = 32768,
             MULTI_STATEMENTS = 65536, MULTI_RESULTS = 131072, PS_MULTI_RESULTS = 262144,
             PLUGIN_AUTH = 524288, CONNECT_ATTRS = 1048576, SESSION_TRACK = 1L << 23,
             SSL_VERIFY_SERVER_CERT = 1L << 30, REMEMBER_OPTIONS = 1L << 31;
        long ALL_FLAGS = LONG_PASSWORD | FOUND_ROWS | LONG_FLAG | CONNECT_WITH_DB | NO_SCHEMA
             | COMPRESS | ODBC | LOCAL_FILES | IGNORE_SPACE | PROTOCOL_41 | INTERACTIVE | SSL
             | IGNORE_SIGPIPE | TRANSACTIONS | RESERVED | SECURE_CONNECTION | MULTI_STATEMENTS
             | MULTI_RESULTS | PS_MULTI_RESULTS | PLUGIN_AUTH | CONNECT_ATTRS | SESSION_TRACK
             | SSL_VERIFY_SERVER_CERT | REMEMBER_OPTIONS;
        long BASIC_FLAGS = ALL_FLAGS & ~(SSL | COMPRESS | SSL_VERIFY_SERVER_CERT);

        constant(runtime, klass, "LONG_PASSWORD", LONG_PASSWORD);
        constant(runtime, klass, "FOUND_ROWS", FOUND_ROWS);
        constant(runtime, klass, "LONG_FLAG", LONG_FLAG);
        constant(runtime, klass, "CONNECT_WITH_DB", CONNECT_WITH_DB);
        constant(runtime, klass, "NO_SCHEMA", NO_SCHEMA);
        constant(runtime, klass, "COMPRESS", COMPRESS);
        constant(runtime, klass, "ODBC", ODBC);
        constant(runtime, klass, "LOCAL_FILES", LOCAL_FILES);
        constant(runtime, klass, "IGNORE_SPACE", IGNORE_SPACE);
        constant(runtime, klass, "PROTOCOL_41", PROTOCOL_41);
        constant(runtime, klass, "INTERACTIVE", INTERACTIVE);
        constant(runtime, klass, "SSL", SSL);
        constant(runtime, klass, "IGNORE_SIGPIPE", IGNORE_SIGPIPE);
        constant(runtime, klass, "TRANSACTIONS", TRANSACTIONS);
        constant(runtime, klass, "RESERVED", RESERVED);
        constant(runtime, klass, "SECURE_CONNECTION", SECURE_CONNECTION);
        constant(runtime, klass, "MULTI_STATEMENTS", MULTI_STATEMENTS);
        constant(runtime, klass, "PS_MULTI_RESULTS", PS_MULTI_RESULTS);
        constant(runtime, klass, "CONNECT_ATTRS", CONNECT_ATTRS);
        constant(runtime, klass, "SESSION_TRACK", SESSION_TRACK);
        constant(runtime, klass, "SSL_VERIFY_SERVER_CERT", SSL_VERIFY_SERVER_CERT);
        constant(runtime, klass, "REMEMBER_OPTIONS", REMEMBER_OPTIONS);
        constant(runtime, klass, "ALL_FLAGS", ALL_FLAGS);
        constant(runtime, klass, "BASIC_FLAGS", BASIC_FLAGS);

        // SSL modes (mysql_ssl_mode enum)
        constant(runtime, klass, "SSL_MODE_DISABLED", 1);
        constant(runtime, klass, "SSL_MODE_PREFERRED", 2);
        constant(runtime, klass, "SSL_MODE_REQUIRED", 3);
        constant(runtime, klass, "SSL_MODE_VERIFY_CA", 4);
        constant(runtime, klass, "SSL_MODE_VERIFY_IDENTITY", 5);

        // enum_session_state_type
        constant(runtime, klass, "SESSION_TRACK_SYSTEM_VARIABLES", 0);
        constant(runtime, klass, "SESSION_TRACK_SCHEMA", 1);
        constant(runtime, klass, "SESSION_TRACK_STATE_CHANGE", 2);
        constant(runtime, klass, "SESSION_TRACK_GTIDS", 3);
        constant(runtime, klass, "SESSION_TRACK_TRANSACTION_CHARACTERISTICS", 4);
        constant(runtime, klass, "SESSION_TRACK_TRANSACTION_STATE", 5);

        // enum mysql_option MYSQL_OPTION_MULTI_STATEMENTS_*
        constant(runtime, klass, "OPTION_MULTI_STATEMENTS_ON", 0);
        constant(runtime, klass, "OPTION_MULTI_STATEMENTS_OFF", 1);
    }

    private static void constant(Ruby runtime, RubyClass klass, String name, long value) {
        klass.defineConstant(name, runtime.newFixnum(value));
    }

    // --- helpers -------------------------------------------------------------

    // Byte-level SQL escaping. Like mysql2/mysql_real_escape_string, the original
    // string object is returned unchanged when nothing needs escaping.
    private static IRubyObject escapeString(Ruby runtime, RubyString str) {
        ByteList in = str.getByteList();
        int n = in.length();
        int first = -1;
        for (int i = 0; i < n; i++) {
            if (needsEscape((byte) in.get(i))) {
                first = i; break;
            }
        }
        if (first < 0) return str;

        ByteList out = new ByteList(n + 8);
        out.setEncoding(in.getEncoding());
        out.append(in.getUnsafeBytes(), in.begin(), first); // unescaped prefix
        for (int i = first; i < n; i++) {
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

    private static boolean needsEscape(byte b) {
        return b == 0 || b == '\n' || b == '\r' || b == 0x1a || b == '\\' || b == '\'' || b == '"';
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
            // A present key wins even if its value is nil (e.g. database: nil
            // overriding a string "database" key), matching mysql2's symbolized opts.
            IRubyObject v = opts.fastARef(runtime.newSymbol(name));
            if (v == null) v = opts.fastARef(runtime.newString(name));
            if (v != null) return v;
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
