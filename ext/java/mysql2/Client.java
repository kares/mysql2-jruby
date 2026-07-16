package mysql2;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mysql.cj.Constants;
import com.mysql.cj.NativeSession;
import com.mysql.cj.exceptions.CJException;
import com.mysql.cj.jdbc.ConnectionImpl;
import com.mysql.cj.jdbc.JdbcStatement;
import com.mysql.cj.jdbc.NonRegisteringDriver;
import com.mysql.cj.jdbc.exceptions.SQLExceptionsMapping;
import com.mysql.cj.protocol.ProtocolEntityFactory;
import com.mysql.cj.protocol.Resultset;
import com.mysql.cj.protocol.a.NativeMessageBuilder;
import com.mysql.cj.protocol.a.NativePacketPayload;
import com.mysql.cj.protocol.a.NativeProtocol;
import com.mysql.cj.protocol.a.ResultsetFactory;

import org.jcodings.Encoding;
import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyClass;
import org.jruby.RubyException;
import org.jruby.RubyHash;
import org.jruby.RubyInteger;
import org.jruby.RubyObject;
import org.jruby.RubyRational;
import org.jruby.RubyString;
import org.jruby.RubyTime;
import org.jruby.anno.JRubyMethod;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.Block;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;
import org.jruby.util.io.EncodingUtils;

public class Client extends RubyObject {
    public static final ObjectAllocator ALLOCATOR = Client::new;

    private static final Driver DRIVER = createMySQLDriver();

    static Driver createMySQLDriver() {
        try {
            return new NonRegisteringDriver();
        } catch (Exception e) {
            throw new AssertionError("Could not create the Connector/J driver", e);
        }
    }

    private Connection connection;
    private RubyHash queryOptions;
    private long affectedRows;
    private long lastId;
    private String queryInfoString; // mysql_info() of the last query, or null
    private int warningCount; // server warnings from the last query
    private short readTimeoutSeconds = -1; // configured :read_timeout
    private Encoding clientEncoding; // default: UTF8Encoding.INSTANCE;

    private String jdbcUrl;
    private Properties jdbcProperties;
    private String connEncoding, connInitCommand;
    private boolean connMultiStatements, connReconnect;

    private transient NativeSession nativeSession;
    private transient ProtocolEntityFactory<Resultset, NativePacketPayload> resultSetFactory;

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
        RubyHash opts = optsArg.isNil() ? RubyHash.newSmallHash(runtime) : (RubyHash) optsArg;

        this.queryOptions = defaultQueryOptions(context, null)
                .merge(context, new IRubyObject[] { opts }, Block.NULL_BLOCK);

        String user = str(opt(context, opts, "username", "user"));
        String pass = str(opt(context, opts, "password", "pass"));

        checkAndCleanQueryOptions(context);

        String encoding = orDefault(strType(context, opt(context, opts, "encoding")), "utf8mb4");
        this.clientEncoding = resolveEncoding(runtime, encoding);

        this.jdbcUrl = buildJdbcUrl(context, opts).toString();
        this.jdbcProperties = new Properties();
        if (user != null) jdbcProperties.setProperty("user", user);
        if (pass != null) jdbcProperties.setProperty("password", pass);

        this.connEncoding = encoding;
        this.connInitCommand = str(opt(context, opts, "init_command"));
        this.connMultiStatements = isMultiStatements(context, opts);
        this.connReconnect = bool(opt(context, opts, "reconnect"), false);

        callMethod(context, "connect");
        return context.nil;
    }

    // mysql2 performs the actual connection in a separate, overridable #connect
    // NOTE: redundant except for mysql2's suite stubs it to avoid connecting...
    @JRubyMethod(name = "connect", visibility = Visibility.PRIVATE, rest = true)
    public IRubyObject connect(ThreadContext context, IRubyObject[] args) {
        this.connection = null;
        try {
            establishConnection(context);
        } catch (Exception e) {
            throw errorFrom(context, e);
        }
        return this;
    }

    private StringBuilder buildJdbcUrl(ThreadContext context, RubyHash opts) {
        String host = orDefault(str(opt(context, opts, "host", "hostname")), "localhost");
        IRubyObject portObj = opt(context, opts, "port");
        int port = portObj.isNil() ? 3306 : portObj.convertToInteger().getIntValue();
        String database = orDefault(str(opt(context, opts, "database", "dbname", "db")), "");

        // NOTE: SSL is deliberately left off for now (useSSL=false)
        StringBuilder url = new StringBuilder("jdbc:mysql://").append(host).append(':').append(port)
                .append('/').append(database)
                .append("?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8");
        // mysql2 rejects negative timeouts (write_timeout has no JDBC analog, but is still validated)
        Integer connectTimeout = intOpt(context, opts, "connect_timeout", true);
        if (connectTimeout != null) url.append("&connectTimeout=").append(connectTimeout * 1000L);
        intOpt(context, opts, "write_timeout", true);
        Integer readTimeout = intOpt(context, opts, "read_timeout", true);
        if (readTimeout != null) {
            this.readTimeoutSeconds = readTimeout.shortValue();
            // read_timeout > 0 maps to JDBC socketTimeout (seconds -> ms)
            // read_timeout == 0 is applied after connect; JDBC treats socketTimeout = 0  as "infinite",
            // the opposite of mysql2's "fail unless the response is ready ~immediately
            if (readTimeout > 0) url.append("&socketTimeout=").append(readTimeout * 1000L);
        }
        // NOTE: JDBC autoReconnect is deliberately not used; it's deprecated and fails to
        // reconnect after the server drops the connection; #ping reconnects manually instead
        if (bool(opt(context, opts, "local_infile"), false)) url.append("&allowLoadLocalInfile=true");

        return url;
    }

    /**
     * Open the JDBC connection and apply the mysql2-equivalent session setup.
     * Reused by #ping when reconnect: true, so init_command etc. are re-sent on reconnect.
     */
    private Connection establishConnection(ThreadContext context) throws SQLException {
        this.connection = DRIVER.connect(jdbcUrl, jdbcProperties);
        this.nativeSession = null; // re-resolve for the new physical connection

        // Make result strings come back in the client encoding (like mysql2's character_set_results),
        // so field metadata reports that charset and getBytes() yields bytes we can tag correctly
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET character_set_results = " + sanitizeCharset(connEncoding));
        }

        if (connInitCommand != null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(connInitCommand);
            }
        }

        if (connMultiStatements) {
            nativeSessionImpl(context, connection).enableMultiQueries();
        }

        // read_timeout: 0 -> a 1ms network timeout, applied now so the handshake and the
        // setup queries above aren't aborted by it. Models mysql2's non-blocking poll
        // (read_timeout:0 fails a query whose response isn't essentially immediate).
        if (readTimeoutSeconds == 0) {
            connection.setNetworkTimeout(Runnable::run, 1);
        }
        return connection;
    }

    /** :flags may be an Integer bitmask or an Array of flag names; detect MULTI_STATEMENTS. */
    private static boolean isMultiStatements(ThreadContext context, RubyHash opts) {
        final long MULTI_STATEMENTS = 65536;

        IRubyObject flags = opt(context, opts, "flags");
        if (flags == null || flags.isNil()) return false;
        if (flags instanceof RubyArray<?>) {
            final RubyArray<?> flagsAry = (RubyArray<?>) flags;
            for (int i = 0; i < flagsAry.size(); i++) {
                final IRubyObject e = flagsAry.eltInternal(i);
                if (e != null && !e.isNil() && "MULTI_STATEMENTS".equals(e.asJavaString())) return true;
            }
            return false;
        }
        if (flags instanceof RubyInteger) {
            return (((RubyInteger) flags).getLongValue() & MULTI_STATEMENTS) != 0;
        }
        return false;
    }

    /** Integer option (seconds/count), or null when absent or non-numeric. */
    private static Integer intOpt(ThreadContext context, RubyHash opts, String name, boolean checkPositive) {
        IRubyObject v = opt(context, opts, name);
        if (v == null || v.isNil()) return null;
        final int val = v.convertToInteger().getIntValue();
        if (checkPositive && val < 0) {
            throw error(context, name + " must be a positive integer, got: " + v);
        }
        return val;
    }

    @JRubyMethod
    public IRubyObject close(ThreadContext context) {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (Exception e) {
            throw errorFrom(context, e);
        }
        return context.nil;
    }

    @JRubyMethod(name = "closed?")
    public IRubyObject closed_p(ThreadContext context) {
        try {
            return context.runtime.newBoolean(connection == null || connection.isClosed());
        } catch (Exception e) {
            return context.tru;
        }
    }

    @JRubyMethod
    public IRubyObject ping(ThreadContext context) {
        try {
            if (connection != null && connection.isValid(0)) return context.tru;
        } catch (Exception e) {
            // fall through to reconnect handling
        }
        // mysql2's mysql_ping reconnects when reconnect: true (re-running init_command)
        // NOTE: Connector/J's autoReconnect isn't 100% reliable (native-session usage),
        // so re-establish manually
        if (connReconnect) {
            try {
                establishConnection(context);
                return context.tru;
            } catch (Exception ex) {
                return context.fals;
            }
        }
        return context.fals;
    }

    /** The live JDBC connection, guarding operations like mysql2's REQUIRE_CONNECTED:
     *  raises "MySQL client is not connected" once the connection is gone, unless
     *  reconnect: true, in which case it re-establishes first (like libmysql auto-reconnect). */
    private Connection connection(ThreadContext context) {
        try {
            if (connection != null && !connection.isClosed()) return connection;
        } catch (SQLException e) {
            // not really expected to happen (normal isClosed() is a flag check)
        }
        if (connReconnect) {
            try {
                return establishConnection(context);
            } catch (Exception ex) {
                throw errorFrom(context, ex);
            }
        }
        throw error(context, "MySQL client is not connected");
    }

    // mysql_thread_id: the server's id for this connection (used by KILL etc.).
    @JRubyMethod(name = "thread_id")
    public IRubyObject thread_id(ThreadContext context) {
        final NativeSession session = nativeSession(context);
        try {;
             return context.runtime.newFixnum(session.getThreadId());
        } catch (CJException ex) {
            throw errorFrom(context, ex);
        }
    }

    // Client#encoding: the Ruby Encoding object for the connection charset
    @JRubyMethod
    public IRubyObject encoding(ThreadContext context) {
        return context.runtime.getEncodingService().getEncoding(clientEncoding);
    }

    @JRubyMethod(required = 1, optional = 1)
    public IRubyObject query(ThreadContext context, IRubyObject[] args) {
        RubyHash merged = (args.length > 1 && !args[1].isNil())
                ? (RubyHash) queryOptions.callMethod(context, "merge", args[1])
                : queryOptions;

        final NativeSession session = nativeSession(context);
        // send the query as raw bytes (in the connection encoding) rather than a decoded Java
        // String, so bytes the server can't map -- e.g. invalid UTF-8 -- reach it intact and are
        // echoed/scrubbed the way libmysqlclient sees them
        final byte[] sqlBytes = queryBytes(context, args[0]);
        try {
            NativeProtocol protocol = session.getProtocol();
            NativeMessageBuilder messageBuilder =
                    (com.mysql.cj.protocol.a.NativeMessageBuilder) protocol.getMessageBuilder();
            NativePacketPayload packet =
                    messageBuilder.buildComQuery(protocol.getSharedSendPacket(), session, sqlBytes);
            final Resultset result = session.execSQL(
                    null,
                    null,
                    -1,
                    packet,
                    false,
                    resultSetFactory(),
                    null,
                    false
            );

            String info = result.getServerInfo();
            this.queryInfoString = (info == null || info.isEmpty()) ? null : info;
            this.warningCount = protocol.getWarningCount();

            if (result.hasRows()) {
                this.affectedRows = 0;
                this.lastId = 0;
                return buildResult(context, new CJRowSource(result), merged, false);
            }
            this.affectedRows = Math.max(result.getUpdateCount(), 0);
            this.lastId = Math.max(result.getUpdateID(), 0);

            return context.nil;
        } catch (CJException e) {
            throw errorFrom(context, e);
        }
    }

    /*
    private IRubyObject queryViaJdbc(ThreadContext context, IRubyObject sqlArg, RubyHash merged) {
        String sql = sqlArg.convertToString().asJavaString();
        Statement stmt = null;
        try {
            stmt = connection(context).createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            boolean hasResult = stmt.execute(sql, Statement.RETURN_GENERATED_KEYS);
            this.queryInfoString = extractServerInfo(stmt);
            this.warningCount = countWarnings(stmt);
            if (hasResult) {
                return buildResult(context, new JdbcRowSource(stmt.getResultSet(), stmt), merged, false);
            }
            this.affectedRows = Math.max(stmt.getUpdateCount(), 0);
            ResultSet keys = stmt.getGeneratedKeys();
            this.lastId = keys.next() ? keys.getLong(1) : 0;
            stmt.close();
            return context.nil;
        } catch (SQLException e) {
            try { if (stmt != null) stmt.close(); } catch (SQLException ignore) { }
            throw errorFrom(context, e);
        }
    } */

    /** Query text as bytes in the connection encoding (mysql2's rb_str_export_to_enc) */
    private byte[] queryBytes(ThreadContext context, IRubyObject sqlArg) {
        RubyString str = sqlArg.convertToString();
        if (str.getEncoding() == clientEncoding) return str.getBytes();

        str = EncodingUtils.strConvEnc(context, str, str.getEncoding(), clientEncoding);
        return str.getBytes();
    }

    @JRubyMethod(name = "prepare")
    public mysql2.Statement prepare(ThreadContext context, IRubyObject sql) {
        try {
            final String sqlString = sql.convertToString().asJavaString();
            PreparedStatement ps = connection(context).prepareStatement(sqlString, Statement.RETURN_GENERATED_KEYS);
            RubyClass Statement = context.runtime.getModule("Mysql2").getClass("Statement");
            return new mysql2.Statement(context.runtime, Statement, this, ps);
        } catch (SQLException e) {
            throw errorFrom(context, e);
        }
    }

    /** Build a Mysql2::Result over a row source (Connector/J Resultset or JDBC ResultSet).
     *  fromStatement marks prepared-statement results (each without a block raises). */
    Result buildResult(ThreadContext context, RowSource source, RubyHash merged, boolean fromStatement) {
        Ruby runtime = context.runtime;
        RowReader reader = new RowReader(
                runtime, source,
                "array".equals(str(hash(context, merged, "as"))),
                bool(hash(context, merged, "cast"), true),
                bool(hash(context, merged, "cast_booleans"), false),
                bool(hash(context, merged, "symbolize_keys"), false),
                orDefault(str(hash(context, merged, "database_timezone")), "local"),
                str(hash(context, merged, "application_timezone")));
        boolean cacheRows = bool(hash(context, merged, "cache_rows"), true);
        RubyClass resultClass = runtime.getModule("Mysql2").getClass("Result");
        return new Result(runtime, resultClass, reader, cacheRows, fromStatement);
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
        return readTimeoutSeconds < 0 ? context.nil : context.runtime.newFixnum(readTimeoutSeconds);
    }

    @JRubyMethod(name = "warning_count")
    public IRubyObject warning_count(ThreadContext context) {
        return context.runtime.newFixnum(warningCount);
    }

    // mysql_set_server_option (COM_SET_OPTION)
    // Rails brackets execute_batch with OPTION_MULTI_STATEMENTS_ON/OFF;
    // Returns true when the option is applied, false for anything we don't handle
    @JRubyMethod(name = "set_server_option")
    public IRubyObject set_server_option(ThreadContext context, IRubyObject value) {
        final long opt = value.convertToInteger().getLongValue();
        final NativeSession session = nativeSession(context);
        try {
            if (opt == 0) {        // MYSQL_OPTION_MULTI_STATEMENTS_ON
                session.enableMultiQueries();
            } else if (opt == 1) { // MYSQL_OPTION_MULTI_STATEMENTS_OFF
                session.disableMultiQueries();
            } else {
                return context.fals;
            }
            return context.tru;
        } catch (RuntimeException e) {
            return context.nil;
        }
    }

    // NOTE: #query already closes its statement, which drains any pending results of a
    // multi-statement query; implemented as ActiveRecord calls this after every query
    @JRubyMethod(name = "abandon_results!")
    public IRubyObject abandon_results(ThreadContext context) {
        return context.nil;
    }

    final NativeSession nativeSession(ThreadContext context) {
        // a reconnect inside connection() resets nativeSession, so a stale
        // session cached from a since-closed connection isn't handed back
        Connection connection = connection(context);
        if (nativeSession == null) {
            return nativeSessionImpl(context, connection);
        }
        return nativeSession;
    }

    private NativeSession nativeSessionImpl(ThreadContext context, Connection connection) {
        try {
            return nativeSession = connection.unwrap(ConnectionImpl.class).getSession();
        } catch (Exception ex) {
            throw errorFrom(context, ex);
        }
    }

    private ProtocolEntityFactory<Resultset, NativePacketPayload> resultSetFactory() {
        if (resultSetFactory == null) {
            resultSetFactory = new ResultsetFactory(
                    Resultset.Type.FORWARD_ONLY,
                    Resultset.Concurrency.READ_ONLY
            );
        }
        return resultSetFactory;
    }

    // in mysql2 prevents a forked child's GC from closing parent's shared MySQL socket
    @JRubyMethod(name = "automatic_close?")
    public IRubyObject automatic_close_p(ThreadContext context) {
        return context.nil;
    }

    @JRubyMethod(name = "automatic_close=")
    public IRubyObject set_automatic_close(ThreadContext context, IRubyObject value) {
        return context.nil;
    }

    private static int countWarnings(final Statement statement) {
        try {
            int count = 0;
            SQLWarning warnings = statement.getWarnings();
            while (warnings != null) {
                count++;
                warnings = warnings.getNextWarning();
            }
            return count;
        } catch (SQLException e) {
            return 0;
        }
    }

    @JRubyMethod(name = "query_info_string")
    public IRubyObject query_info_string(ThreadContext context) {
        return queryInfoString == null ? context.nil : context.runtime.newString(queryInfoString);
    }

    @JRubyMethod(name = "query_info")
    public RubyHash query_info(ThreadContext context) {
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

    /** MySQL's mysql_info() for the last query, via Connector/J's internal result. */
    private static String extractServerInfo(Statement stmt) {
        if (!(stmt instanceof JdbcStatement)) return null;
        try {
            Resultset rs = ((JdbcStatement) stmt).getResultSetInternal();
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
            throw errorFrom(context, e);
        }
    }

    @JRubyMethod(name = "select_db")
    public IRubyObject select_db(ThreadContext context, IRubyObject db) {
        try {
            // setDatabase() sends COM_INIT_DB directly, unlike setCatalog() whose meaning
            // depends on Connector/J's databaseTerm setting.
            connection(context).unwrap(com.mysql.cj.jdbc.ConnectionImpl.class)
                    .setDatabase(db.convertToString().asJavaString());
        } catch (SQLException e) {
            throw errorFrom(context, e);
        }
        return db;
    }

    @JRubyMethod(name = "server_info")
    public IRubyObject server_info(ThreadContext context) {
        final Ruby runtime = context.runtime;
        try {
            String version = connection(context).getMetaData().getDatabaseProductVersion();
            RubyHash info = RubyHash.newHash(runtime);
            info.fastASet(runtime.newSymbol("id"), runtime.newFixnum(serverVersionId(version)));
            // tag with the connection encoding, exported to Encoding.default_internal
            info.fastASet(runtime.newSymbol("version"), encodedString(context, version));
            return info;
        } catch (SQLException e) {
            throw errorFrom(context, e);
        }
    }

    /** mysql_get_server_version() equivalent: major*10000 + minor*100 + patch */
    private static long serverVersionId(String version) {
        Matcher matcher = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(version);
        if (!matcher.find()) return 0;
        return Long.parseLong(matcher.group(1)) * 10000 + Long.parseLong(matcher.group(2)) * 100 + Long.parseLong(matcher.group(3));
    }

    /** Build a String tagged with the connection encoding, exported to default_internal */
    private RubyString encodedString(ThreadContext context, String s) {
        Ruby runtime = context.runtime;
        RubyString str = runtime.newString(new ByteList(s.getBytes(StandardCharsets.UTF_8), clientEncoding, false));
        Encoding internalEnc = runtime.getDefaultInternalEncoding();
        if (internalEnc != null && internalEnc != clientEncoding) {
            return EncodingUtils.strConvEnc(context, str, clientEncoding, internalEnc);
        }
        return str;
    }

    private static Encoding resolveEncoding(Ruby runtime, String mysqlCharset) {
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

    @JRubyMethod(name = "info")
    public IRubyObject info(ThreadContext context) {
        return info(context, null);
    }

    @JRubyMethod(meta = true, name = "info")
    public static IRubyObject info(ThreadContext context, IRubyObject self) {
        return infoHash(context.runtime, DRIVER.getMajorVersion(), DRIVER.getMinorVersion(), Constants.CJ_FULL_NAME);
    }

    private static RubyHash infoHash(Ruby runtime, int major, int minor, String name) {
        int patch = parsePatch(name, major, minor);
        RubyHash hash = RubyHash.newHash(runtime);
        hash.fastASet(runtime.newSymbol("id"), runtime.newFixnum(major * 10000L + minor * 100L + patch));
        hash.fastASet(runtime.newSymbol("version"), usAscii(runtime, major + "." + minor + "." + patch));
        hash.fastASet(runtime.newSymbol("header_version"), usAscii(runtime, name == null ? "" : name));
        return hash;
    }

    /** Extract the patch component from a driver version string like "9.4.0" */
    private static int parsePatch(String raw, int major, int minor) {
        if (raw == null) return 0;
        Matcher matcher = Pattern.compile("\\b" + major + "\\." + minor + "\\.(\\d+)").matcher(raw);
        try {
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static RubyString usAscii(Ruby runtime, String s) {
        return runtime.newString(new ByteList(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                org.jcodings.specific.USASCIIEncoding.INSTANCE, false));
    }

    private static transient RubyHash defaultQueryOptions;

    @JRubyMethod(meta = true, name = "default_query_options")
    public static RubyHash defaultQueryOptions(ThreadContext context, IRubyObject self) {
        if (defaultQueryOptions == null) {
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
            defaultQueryOptions = (RubyHash) hash.freeze(context);
        }
        return defaultQueryOptions;
    }

    /** Local timezone offset as a day fraction (Rational); matches mysql2 */
    @JRubyMethod(meta = true, name = "local_offset")
    public static IRubyObject localOffset(ThreadContext context, IRubyObject self) {
        return localOffset(context);
    }

    // 2010-01-01 UTC: a fixed winter reference so the offset is the zone's standard (non-DST)
    // offset, stable year-round -- matches mysql2's Time.local(2010).utc_offset.
    private static final long LOCAL_OFFSET_REF_MILLIS = 1262304000000L;

    static IRubyObject localOffset(ThreadContext context) {
        final Ruby runtime = context.runtime;
        int offset = RubyTime.getLocalTimeZone(runtime).getOffset(LOCAL_OFFSET_REF_MILLIS) / 1000;
        return RubyRational.newRationalCanonicalize(context, offset, 86400);
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

    static RubyClass errorClass(Ruby runtime) {
        return runtime.getModule("Mysql2").getClass("Error");
    }

    static RaiseException error(ThreadContext context, String msg) {
        return context.runtime.newRaiseException(errorClass(context.runtime), msg);
    }

    RuntimeException errorFrom(ThreadContext context, Exception e) {
        if (e instanceof SQLException) return errorFrom(context, (SQLException) e);
        if (e instanceof CJException)  return errorFrom(context, (CJException) e);
        return (RaiseException) error(context, e.getMessage()).initCause(e);
    }

    RuntimeException errorFrom(ThreadContext context, SQLException e) {
        return buildError(context, e.getErrorCode(), e.getSQLState(), e.getMessage(), isSocketTimeout(e));
    }

    RuntimeException errorFrom(ThreadContext context, CJException e) {
        // Reuse Connector/J's own CJException -> SQLException mapping so we get the standard
        // SQLState (e.g. 08S01 for a communications link failure) that error.rb routes to
        // ConnectionError, rather than the generic S1000 the raw CJException reports
        return errorFrom(context, SQLExceptionsMapping.translateException(e));
    }

    private RaiseException buildError(ThreadContext context, int code, String sqlStateStr, String message, boolean timeout) {
        Ruby runtime = context.runtime;
        // A vendor code of 0 means the driver raised it, not the server (no MySQL errno);
        // mysql2 reports nil there, and ActiveRecord keys its "not connected" branch off nil
        IRubyObject errno = code == 0 ? runtime.getNil() : runtime.newFixnum(code);
        IRubyObject sqlState = sqlStateStr == null ? runtime.getNil() : runtime.newString(sqlStateStr);
        IRubyObject msg = runtime.newString(message == null ? "" : message);

        // a read_timeout (JDBC socketTimeout) throws a CommunicationsException wrapping a
        // java.net.SocketTimeoutException. mysql2 surfaces this as Mysql2::Error::TimeoutError
        // (with nil errno), which ActiveRecord maps to AdapterTimeout; the generic 08-state
        // routing would otherwise mislabel it as a connection failure.
        if (timeout) {
            RubyClass timeoutClass = (RubyClass) errorClass(runtime).getConstant("TimeoutError");
            RubyException exc = (RubyException) timeoutClass.callMethod(context, "new",
                    new IRubyObject[]{ msg, runtime.getNil(), runtime.getNil(), sqlState });
            return exc.toThrowable();
        }

        RubyException exc = (RubyException) errorClass(runtime).callMethod(context, "new_with_args",
                new IRubyObject[]{msg, runtime.getNil(), errno, sqlState});
        return exc.toThrowable();
    }

    private static boolean isSocketTimeout(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SocketTimeoutException) return true;
            if (c == c.getCause()) break;
        }
        return false;
    }

    static IRubyObject opt(ThreadContext context, RubyHash opts, String name) {
        IRubyObject v = optInternal(context.runtime, opts, name);
        return v == null ? context.nil : v;
    }

    static IRubyObject opt(ThreadContext context, RubyHash opts, String... names) {
        for (String name : names) {
            IRubyObject v = optInternal(context.runtime, opts, name);
            if (v != null) return v;
        }
        return context.nil;
    }

    /** Option value by any of the given symbol/string keys. */
    private static IRubyObject optInternal(Ruby runtime, RubyHash opts, String name) {
        // a present key wins even if its value is nil (e.g. database: nil
        // overriding a string "database" key) - matches mysql2's symbolized opts
        IRubyObject v = opts.fastARef(runtime.newSymbol(name));
        if (v == null) v = opts.fastARef(runtime.newString(name));
        return v;
    }

    static IRubyObject hash(ThreadContext context, RubyHash h, String symKey) {
        return h.op_aref(context, context.runtime.newSymbol(symKey));
    }

    static String str(IRubyObject val) {
        // a non-string option value (e.g. a numeric database name) should to_s
        return (val == null || val.isNil()) ? null : ((RubyObject) val).toString();
    }

    /** Like {@link #str} but strict: a non-nil, non-String value raises TypeError
     *  (mysql2's Check_Type(value, T_STRING) on the encoding option). */
    static String strType(ThreadContext context, IRubyObject val) {
        if (val == null || val.isNil()) return null;
        if (!(val instanceof RubyString)) {
            throw context.runtime.newTypeError(val, context.runtime.getString());
        }
        return val.asJavaString();
    }

    static String orDefault(String val, String defaultVal) {
        return val == null ? defaultVal : val;
    }

    static boolean bool(IRubyObject val, boolean defaultVal) {
        return (val == null || val.isNil()) ? defaultVal : val.isTrue();
    }

    /** Whitelist a charset name for safe interpolation into SET character_set_results. */
    private static String sanitizeCharset(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]+")) return "utf8mb4";
        return name;
    }

    // mysql2's check_and_clean_query_options: warn on deprecated option keys and
    // drops credentials so #inspect / #query_options can't leak them
    private void checkAndCleanQueryOptions(ThreadContext context) {
        final Ruby runtime = context.runtime;
        /*
        for (String key : new String[] { "user", "pass", "hostname", "dbname", "db", "sock" }) {
            if (queryOptions.fastARef(runtime.newSymbol(key)) != null) {
                warn(context, "============= WARNING FROM mysql2 =============");
                warn(context, "The options :user, :pass, :hostname, :dbname, :db, and :sock are deprecated and will be removed at some point in the future.");
                warn(context, "Instead, please use :username, :password, :host, :port, :database, :socket, :flags for the options.");
                warn(context, "============= END WARNING FROM mysql2 =========");
                break;
            }
        } */
        queryOptions.delete(context, runtime.newSymbol("password"));
        queryOptions.delete(context, runtime.newSymbol("pass"));
    }

    private static void warn(ThreadContext context, String message) {
        IRubyObject stderr = context.runtime.getGlobalVariables().get("$stderr");
        stderr.callMethod(context, "puts", context.runtime.newString(message));
    }
}
