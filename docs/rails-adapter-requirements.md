# Rails 8.0 ActiveRecord `Mysql2Adapter` — API requirements

What the ActiveRecord `Mysql2Adapter` actually calls on the mysql2 gem, derived from
reading Rails **8.0.5** (`activerecord/lib/active_record/connection_adapters/`):

- `mysql2_adapter.rb` — the concrete adapter
- `mysql2/database_statements.rb` — query/prepare/result execution
- `abstract_mysql_adapter.rb` / `mysql/database_statements.rb` — shared MySQL layer

The adapter only ever touches the gem through `@raw_connection` (a `Mysql2::Client`),
the `Mysql2::Result` it hands back, `Mysql2::Statement` (prepared), a handful of
`Mysql2::Client` constants, and `Mysql2::Error`. Everything else is plain SQL. So the
gem's obligation to Rails is small and fully enumerated below.

Status legend: ✅ implemented · ⚠️ partial / stubbed · ❌ missing (blocks Rails)

---

### 1. `Mysql2::Client`

### Construction
| API | Used by | Status | Notes |
|-----|---------|--------|-------|
| `Mysql2::Client.new(config)` | `new_client` (mysql2_adapter.rb:25) | ✅ | `config` is the **whole AR `configuration_hash`** (symbol keys), not a curated option set. We map host/port/user/password/database/encoding/collation/timeouts/flags/init_command/reconnect/local_infile/SSL/public-key options, and silently ignore unknown AR-only keys (`:adapter`, `:pool`, `:checkout_timeout`, …). |

The config hash Rails passes includes at least: `:host :port :username :password :database
:encoding :collation :flags :socket :read_timeout :write_timeout :connect_timeout
:reconnect :init_command :sslca :sslcert :sslkey :sslcapath :sslcipher :ssl_mode
:secure_auth :default_file :default_group :variables :sql_mode` plus AR-only noise
(`:adapter :pool :checkout_timeout` …). Unknown keys must be tolerated.

### Methods called on the live connection
| API | Used by | Status | Notes |
|-----|---------|--------|-------|
| `#query(sql)` | `perform_query` (db_statements.rb:54) | ✅ | Returns `Mysql2::Result` for SELECT, `nil` for DML. Reads **live** `query_options` at call time (Rails mutates it — see below). |
| `#prepare(sql)` | `perform_query` (:63, :74) | ✅ | Returns `Mysql2::Statement`. Cached in `@statements` or closed eagerly. |
| `#escape(str)` | `quote_string` (abstract:698) | ✅ | Via `with_raw_connection { \|c\| c.escape(str) }`. |
| `#ping` | `active?` (mysql2_adapter:111) | ✅ | Reconnects manually when `reconnect: true`. |
| `#close` | `disconnect!` / `reconnect` (:126,:153) | ✅ | |
| `#closed?` | `connected?` (:105) | ✅ | |
| `#affected_rows` | `perform_query` (:60) | ✅ | Fallback when `result.size` is nil. |
| `#last_id` | `last_inserted_id` (db_statements.rb:27) | ✅ | Non-RETURNING path (MySQL, not MariaDB). |
| `#server_info` → `[:version]` | `get_full_version` (mysql2_adapter:169) | ✅ | Returns a Hash with `:id` and `:version` string. Feeds version gating (`supports_json?` etc.). |
| `#query_options` (mutable Hash) | `configure_connection` (:159), `perform_query` (:49) | ✅ | Rails **writes** into it: `[:as] = :array`, `[:database_timezone] = default_timezone`. Our accessor returns the live hash and `#query` re-reads it, so mutations take effect. **Keep it the same object.** |
| `#warning_count` | `handle_warnings` (abstract:771) | ✅ | Guard before `SHOW WARNINGS`; updated from protocol warning count after each query. |
| `#automatic_close = false` | `discard!` (mysql2_adapter:134) | ⚠️ | No-op stub returning `nil`. Fine — it only matters for fork/GC semantics the C-ext has and JDBC doesn't. |
| `#set_server_option(flag)` | `perform_query` batch path (:43,:107) | ✅ | Maps `OPTION_MULTI_STATEMENTS_ON` / `_OFF` to `NativeSession#enableMultiQueries()` / `disableMultiQueries()` (sends `COM_SET_OPTION`). |
| `#abandon_results!` | `perform_query` (:100) | ✅ | Called after every query. No-op returning `nil` (`#query` drains any remaining results). |

---

## 2. `Mysql2::Result`

Returned by `#query` and `Statement#execute`; consumed in `cast_result` /
`affected_rows` (mysql2/database_statements.rb) and `handle_warnings`.

| API | Used by | Status | Notes |
|-----|---------|--------|-------|
| `#fields` | `cast_result` (:114) | ✅ | Column names → `ActiveRecord::Result.new(fields, rows)`. |
| `#to_a` | `cast_result` (:119) | ✅ | Via `Enumerable`. With `query_options[:as] = :array` each row is an **Array**. |
| `#size` | `perform_query` (:60,:98) | ✅ | Row count; also the `affected_rows` fallback for SELECT. |
| `#count` | `handle_warnings` (abstract:777) | ✅ | On the `SHOW WARNINGS` result. |
| `#each { \|level, code, msg\| }` | `handle_warnings` (:778) | ✅ | Yields arrays because `as: :array` is set globally. |
| `#free` | `free_raw_result` (:134) | ✅ | |
| `instance_variable_set/get(:@_ar_stmt_to_close)` | `perform_query` (:87), `free_raw_result` (:135) | ✅ | Works on any `RubyObject`; Rails stashes the stmt to close alongside the result. |

**Requirement:** with `query_options[:as] = :array` set post-construction, results
**must** materialize rows as Arrays. This is set once in `configure_connection` and is
the format for the whole connection. (Verified our `#query` reads `as` from the live
merged options.)

---

## 3. `Mysql2::Statement` (prepared, `default_prepared_statements` is `false` but still used when `prepared_statements: true` config)

| API | Used by | Status | Notes |
|-----|---------|--------|-------|
| `#execute(*type_casted_binds)` | `perform_query` (:66,:78) | ✅ | Returns `Mysql2::Result` (SELECT) or `nil` (DML). Updates `Client#last_id` and `Client#affected_rows`. |
| `#affected_rows` | `perform_query` (:67,:79) | ✅ | |
| `#close` | `perform_query` (:89,:92), StatementPool `dealloc` | ✅ | |

Note: Rails' Mysql2 adapter defaults `prepared_statements` to **false**, so the common
path is `Client#query`, not `Statement`. Prepared path is exercised when a user opts in.

---

## 4. Constants on `Mysql2::Client`

| Constant | Used by | Status | Notes |
|----------|---------|--------|-------|
| `FOUND_ROWS` | `initialize` (mysql2_adapter:64) | ✅ | Rails OR-s it into `@config[:flags]` so UPDATE affected-rows counts **matched** rather than changed rows. Mapped to Connector/J's `useAffectedRows=false` when set (`useAffectedRows=true` when omitted, matching libmysqlclient). |
| `MULTI_STATEMENTS` | `multi_statements_enabled?` (db_statements.rb:37) | ✅ | Bitmask / flag name checked at connect time (`NativeSession#enableMultiQueries()`). |
| `OPTION_MULTI_STATEMENTS_ON` / `_OFF` | `perform_query` batch (:43,:107) | ✅ | Consumed by `Client#set_server_option`. |

---

## 5. Implementation status & notes (prioritized)

### P0 — blocks all Rails traffic
- [x] **`Client#abandon_results!`** — added as a no-op returning `nil`. `#query` already
      drains pending multi-results, so nothing lingers.

### P1 — connection options Rails passes and expects honored
Done (`Client#initialize` & `SslSupport`, verified against live MySQL):
- [x] **`flags` handling** — accepts Integer bitmask, Array of flag names, or space-separated
      String (`@config[:flags]` may be any of these — mysql2_adapter:61). `MULTI_STATEMENTS`
      (bit `65536` / `"MULTI_STATEMENTS"`) enables multi-queries once at connect
      (`NativeSession.enableMultiQueries()`), so ActiveRecord's skip-toggle path works.
      Also maps `FOUND_ROWS`, `LOCAL_FILES`, `SSL`, `SSL_VERIFY_SERVER_CERT`, `COMPRESS`,
      and `INTERACTIVE`.
- [x] **`FOUND_ROWS`** — mapped to `useAffectedRows=false` when `FOUND_ROWS` is present in
      `:flags` (as ActiveRecord sets it), and `useAffectedRows=true` when omitted (matching
      libmysqlclient's default changed-rows behavior).
- [x] **timeouts**: `connect_timeout` → `connectTimeout`, `read_timeout` → `socketTimeout`
      (mysql2 seconds → JDBC ms). `read_timeout` is also stored and reported by the accessor.
      `write_timeout` is validated (non-negative) and ignored as it has no JDBC analog.
- [x] **`init_command:`** — executed once after connect (and re-applied on `#ping` reconnect).
- [x] **`reconnect:`** — handled manually in `#ping` (re-establishing the connection and
      re-running session setup / `init_command` rather than relying on deprecated JDBC `autoReconnect`).
- [x] **`local_infile:`** → JDBC `allowLoadLocalInfile`.
- [x] **SSL / TLS**: `ssl_mode` (`:disabled`, `:preferred`, `:required`, `:verify_ca`,
      `:verify_identity` or `SSL_MODE_*` constants), `:sslca`, `:sslcapath`, `:sslcert`,
      `:sslkey`, `:sslcipher`, `:sslverify`, and `Client#ssl_cipher`. Supports both PEM files
      (via ephemeral `PKCS12` keystores) and Java keystores (`.p12`/`.jks`).
- [x] **Public key retrieval**: `:get_server_public_key` → `allowPublicKeyRetrieval`,
      `:server_public_key_path` → `serverRSAPublicKeyFile`, `:default_auth` →
      `defaultAuthenticationPlugin`.
- [x] Unknown AR config keys (`adapter`, `pool`, `checkout_timeout`, …) are tolerated.

Intentionally unsupported (no JDBC equivalent):
- **`socket:`** → unix-socket connect. Connector/J 8+ has no built-in unix-socket support
  out of the box (requires a third-party `SocketFactory` via `:properties`); TCP is used.
- **`default_file:` / `default_group:`** — *libmysqlclient* option-file support
  (ignored rather than crashing).

### P2 — batch / multi-statement — **DONE**
`execute_batch` (mysql2/database_statements.rb:17) combines statements with `;\n` into one
string and drives the connection like this:

```
set_server_option(OPTION_MULTI_STATEMENTS_ON)   # toggle multi-statement mode ON
query("stmt1;\nstmt2;\n…")                        # one multi-statement call
abandon_results!                                  # discard results
set_server_option(OPTION_MULTI_STATEMENTS_OFF)   # toggle it back OFF (ensure block)
```

This powers:
- [x] `begin_isolated_db_transaction` → `SET TRANSACTION ISOLATION LEVEL …; BEGIN`
      (abstract_mysql_adapter:234).
- [x] Fixture / seed loading (`FixturesTest` bulk-insert and `max_allowed_packet` cases).

#### What standard JDBC offers vs. what needs the driver
- **Result iteration** is standard JDBC: after `Statement#execute("a;b")`, walk
  `getResultSet()` / `getUpdateCount()` / `getMoreResults()`. ✅ portable.
- **Toggling server multi-statement mode at runtime** is **not** in the JDBC API. The only
  standard-JDBC way to accept `;`-separated SQL is the connect-time driver URL property
  `allowMultiQueries=true` — but that is (a) connection-global, not toggleable per-call, and
  (b) exactly the always-on posture mysql2 avoids for injection safety. So Rails' ON/OFF
  toggle maps to a **driver-specific** API.

#### Driver-specific mechanism (Connector/J 9.4.0) — the exact mysql2 mirror
`com.mysql.cj.NativeSession` exposes:
- `enableMultiQueries()`  → sends `COM_SET_OPTION` with arg `0` (`MYSQL_OPTION_MULTI_STATEMENTS_ON`)
- `disableMultiQueries()` → sends `COM_SET_OPTION` with arg `1` (`_OFF`)

These are the **identical wire message** the mysql2 C-ext sends for
`set_server_option(OPTION_MULTI_STATEMENTS_ON/OFF)` (`COM_SET_OPTION = 27`, verified in
`buildComSetOption`). Connector/J's own `StatementImpl.executeBatch()` uses this same pair
internally (`executeBatchUsingMultiQueries` brackets the batch with enable/disable), so it
is the sanctioned mechanism, not a hack.

Reachable from the JDBC `Connection` via standard `unwrap`:
```java
NativeSession s = conn.unwrap(com.mysql.cj.jdbc.ConnectionImpl.class).getSession();
s.enableMultiQueries();   // or s.disableMultiQueries();
```
(`enable/disableMultiQueries` are on the concrete `NativeSession`, not the `Session`
interface — so the `ConnectionImpl` unwrap is required.)

#### Empirically validated (live MySQL 8.0 / 8.4)
Connecting **without** `allowMultiQueries`:
1. Plain `execute("SELECT 1; SELECT 2")` → server syntax error. ✔ expected
2. `enableMultiQueries()` then the same `execute` → both result sets returned, iterated via
   `getMoreResults()`. ✔
3. Mixed `SET @x=1;\nSELECT @x+1` (the batch shape: update-count then result set) → works. ✔
4. After `disableMultiQueries()` the multi-statement is rejected again → proves OFF reverts
   **server** state. ✔
5. **Closing the `Statement` after reading only the first of several results leaves the
   connection fully usable** (Connector/J drains pending multi-results on `close()`). ✔ —
   so no explicit draining loop is required for correctness.

#### Concrete implementation (small, faithful) — **DONE**
- [x] **`Client#set_server_option(opt)`** — `0` → `session.enableMultiQueries()` return
      `true`; `1` → `disableMultiQueries()` return `true`; unknown → `false`. Restored the 3
      `set_server_option` return-value specs.
- [x] **`Client#abandon_results!`** — no-op returning `nil` (see P0).
- [x] **`Client#query`** — first result processed, rest drained.
- [x] Helper `nativeSession()` on `Client` — caches `conn.unwrap(ConnectionImpl.class).getSession()`.

**Still out of scope (deferred multi-*client* API):** `next_result`, `store_result`,
`more_results?`, and the "commands out of sync" raise-on-outstanding-results behavior.
Rails does not use these — they back the excluded `client_spec` "Multiple results sets"
examples only.

#### Caveats to document, not blockers
- **Eager execution**: Connector/J executes the whole multi-statement packet before results
  are read, so mysql2's "error surfaces mid-iteration via `next_result`" semantics differ —
  an error in statement N raises from `execute()`, not from a later `more_results?`. Rails'
  `execute_batch` doesn't depend on the mid-iteration timing, so this is acceptable.
- **`affected_rows` after a batch** reflects only the first statement. Rails doesn't rely on
  it for batches (fixtures / isolated-transaction BEGIN).
- **`flags` including `MULTI_STATEMENTS`**: `multi_statements_enabled?` (db_statements.rb:31)
  makes Rails **skip** the toggle and assume the mode is always on. Handled in `Client#connect`
  by calling `enableMultiQueries()` when `MULTI_STATEMENTS` is in `:flags`.

### P3 — correctness polish — **DONE**
- [x] **`Error#error_number` returns the MySQL vendor code** — driver `getErrorCode()` passes
      the server errno through unmodified. Verified against live
      MySQL: `ER_DUP_ENTRY 1062`, `ER_NO_REFERENCED_ROW_2 1452`, `ER_ROW_IS_REFERENCED_2 1451`,
      `ER_BAD_NULL_ERROR 1048`, `ER_DATA_TOO_LONG 1406`, `ER_WARN_DATA_OUT_OF_RANGE 1264`,
      `ER_PARSE_ERROR 1064`, `ER_NO_SUCH_TABLE 1146`, `ER_BAD_DB_ERROR 1049` all correct. So
      AR's `translate_exception` switch (RecordNotUnique, InvalidForeignKey, NotNullViolation,
      ValueTooLong, RangeError, NoDatabaseError, …) dispatches correctly.
- [x] **Connection-error classification** — a driver-side exception carries vendor code `0`
      (no server errno) and a `08`-class SQLSTATE. Now: code `0` → `error_number = nil`
      (matches mysql2, and AR's `when nil` branch); SQLSTATE class `08` (e.g. `08S01`
      communications link failure, `08003` connection does not exist) →
      `Mysql2::Error::ConnectionError`. Verified: a `KILL`ed connection and a query on a
      closed connection both raise `ConnectionError` with `error_number == nil`, so
      mysql2_adapter's `is_a?(::Mysql2::Error::ConnectionError)` branch → `ConnectionFailed`.
- [x] `Error::TimeoutError` for a client-side `read_timeout` — a JDBC `socketTimeout` throws a
      `CommunicationsException` wrapping `java.net.SocketTimeoutException`; `errorFrom`
      detects that in the cause chain and raises `Mysql2::Error::TimeoutError` (nil errno), so
      AR's `mysql2_adapter#translate_exception` maps it to `AdapterTimeout`. A genuine dropped
      connection (KILL, closed socket) has no `SocketTimeoutException` and still routes to
      `ConnectionError`. Confirmed by AR's own `test_read_timeout_exception`. Server-side
      statement timeouts (`ER_QUERY_TIMEOUT 3024`, `ER_QUERY_INTERRUPTED 1317`) come through as
      vendor codes and dispatch correctly.
- [x] **`warning_count`** — updated after every `Client#query` and `Statement#execute` from
      `protocol.getWarningCount()`, powering ActiveRecord's `handle_warnings` when
      `ActiveRecord.db_warnings_action` is set.
- [x] **`server_info` (`:version` and `:id`)** — `server_info[:version]` returns the full
      server version string (which Rails' `version_string` regex `^(?:5\.5\.5-)?(\d+\.\d+\.\d+)`
      and `mariadb?` check parse directly), and `serverVersionId` strips any leading `5.5.5-`
      MariaDB replication prefix before computing `major * 10000 + minor * 100 + patch`.

---

## 6. What Rails does NOT require (scope relief)

- No `async` / `stream: true` from the adapter (results are fully buffered; `as: :array`).
- No `more_results?` / `next_result` / `store_result` — multi-result iteration is only via
  the batch path above, and even there Rails discards results (`abandon_results!`).
- No `session_track` ( note: `thread_id`, `select_db`, and `query_info` are implemented for
  general `mysql2` compatibility even though Rails' adapter doesn't call them directly).
- `Result` only needs `fields`, `to_a`/`each` (as Array), `size`, `count`, `free` for
  ActiveRecord (though Hash-mode and per-call options are also implemented).

---

## 7. Running ActiveRecord's own test suite (JRuby)

Rails' `activerecord/test` files boot on our gem — the stock `Mysql2Adapter` drives our
`Mysql2::Client` unchanged. This is wired up as a rake target (`rake rails:*`, modeled on
activerecord-jdbc-adapter's `rakelib/rails.rake`); the supporting files live under
`test/rails/`:

```sh
export RAILS=../rails                      # a Rails checkout (activerecord/ lives under it)
rake rails:bundle                          # one-time: install test-support gems (network)
rake rails:db:build                        # create activerecord_unittest{,2} + user
rake rails:test                            # full suite (adapters/* limited to mysql2)
rake rails:test TEST=test/cases/transactions_test.rb   # one file / comma-separated list
AR_PREPARED_STATEMENTS=true rake rails:test TEST=...    # prepared-statement path
```

How it fits together:
- **Bundle** (`test/rails/Gemfile`): Rails' own `Gemfile` excludes `mysql2`/`pg`/`trilogy` on
  JRuby and its lockfile lacks the `java` platform, so it can't drive these tests. Ours pulls
  the Rails gems from the `ENV['RAILS']` clone via `path:` and adds the test-support gems
  (mocha, minitest-mock, bcrypt, zeitwerk, rack, tzinfo-data, …). `railties` is deliberately
  *not* bundled (it drags `irb`/`rdoc`/`rbs`, which need a native build) — its `lib` is added
  to the load path by the harness so minitest 6 can autoload `railties/lib/minitest/rails_plugin.rb`.
- **Adapter on our gem** (`test/rails/harness.rb`, loaded via `-rbundler/setup -r harness`):
  requires `mysql2/compat` to register the `mysql2` gem alias in Bundler so the adapter's
  `gem "mysql2", "~> 0.5"` activation resolves to `mysql2-jruby`.
- **Config** (`test/rails/config.yml`, `ARCONFIG`): mysql2 connections with ENV-overridable
  `MYSQL_HOST`/`PORT`/`USER`/`PASSWORD` (default `127.0.0.1:13306` root/root) and
  `AR_PREPARED_STATEMENTS`. `N=0` (minitest parallel crashes on JRuby) and the Connector/J jar
  on `CLASSPATH` are set by the rake task.

### Full Suite Results (Rails 7.2.3.1 & Rails 8.0.5 on CI)

The entire ActiveRecord test suite runs on GitHub Actions against both **Rails 7.2.3.1** and
**Rails 8.0.5** (on MySQL 8.4):

- **Rails 8.0.5**: **8,649 runs, 28,119 assertions, 0 failures, 0 errors** (92 excluded in
  `test/rails/excludes/mysql2/` — 58 tests that hardcode the `sqlite3` C extension and 34
  JRuby-specific issues like `Marshal`, `Array#-` `#hash` range checks, `msgpack-java`, and
  SnakeYAML document size limits).
- **Rails 7.2.3.1**: **8,555+ runs, 0 failures, 0 errors**.

All `mysql2`-specific adapter tests (`adapters/mysql2/*`), transactions & isolation levels,
fixture bulk-inserts, binary/JSON/enum/temporal precision casting, and prepared statement
execution pass with **0 failures**.
