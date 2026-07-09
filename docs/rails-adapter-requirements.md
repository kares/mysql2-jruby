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

## 1. `Mysql2::Client`

### Construction
| API | Used by | Status | Notes |
|-----|---------|--------|-------|
| `Mysql2::Client.new(config)` | `new_client` (mysql2_adapter.rb:25) | ⚠️ | `config` is the **whole AR `configuration_hash`** (symbol keys), not a curated option set. We parse host/port/user/password/database/encoding; we silently ignore the rest (see §5). Must not raise on unknown keys. |

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
| `#ping` | `active?` (mysql2_adapter:111) | ✅ | |
| `#close` | `disconnect!` / `reconnect` (:126,:153) | ✅ | |
| `#closed?` | `connected?` (:105) | ✅ | |
| `#affected_rows` | `perform_query` (:60) | ✅ | Fallback when `result.size` is nil. |
| `#last_id` | `last_inserted_id` (db_statements.rb:27) | ✅ | Non-RETURNING path (MySQL, not MariaDB). |
| `#server_info` → `[:version]` | `get_full_version` (mysql2_adapter:169) | ✅ | Must return a Hash with a `:version` string. Feeds version gating (`supports_json?` etc.). |
| `#query_options` (mutable Hash) | `configure_connection` (:159), `perform_query` (:49) | ✅ | Rails **writes** into it: `[:as] = :array`, `[:database_timezone] = default_timezone`. Our accessor returns the live hash and `#query` re-reads it, so mutations take effect. **Keep it the same object.** |
| `#warning_count` | `handle_warnings` (abstract:771) | ✅ | Guard before `SHOW WARNINGS`. |
| `#automatic_close = false` | `discard!` (mysql2_adapter:134) | ⚠️ | No-op stub returning the value. Fine — it only matters for fork/GC semantics the C-ext has and JDBC doesn't. |
| `#set_server_option(flag)` | `perform_query` batch path (:43,:107) | ⚠️ | No-op returning `nil`. **Combined with the multi-statement gap this breaks `execute_batch` — see §4.** |
| **`#abandon_results!`** | `perform_query` (:100) | ❌ | **Called after EVERY query.** Currently `NoMethodError` → breaks all Rails queries. Must exist. For us it is a **no-op** (single result set, JDBC drains it) — just define it returning `nil`. **Highest priority.** |

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
| `#execute(*type_casted_binds)` | `perform_query` (:66,:78) | ✅ | Returns `Mysql2::Result` (SELECT) or `nil` (DML). |
| `#affected_rows` | `perform_query` (:67,:79) | ✅ | |
| `#close` | `perform_query` (:89,:92), StatementPool `dealloc` | ✅ | |

Note: Rails' Mysql2 adapter defaults `prepared_statements` to **false**, so the common
path is `Client#query`, not `Statement`. Prepared path is exercised when a user opts in.

---

## 4. Constants on `Mysql2::Client`

| Constant | Used by | Status | Notes |
|----------|---------|--------|-------|
| `FOUND_ROWS` | `initialize` (mysql2_adapter:64) | ⚠️ | Rails OR-s it into `@config[:flags]` so UPDATE affected-rows counts **matched** rather than changed rows. Constant is **defined**, but we do **not apply the flag to the JDBC connection** (`useAffectedRows` handling). See §5 — affects `affected_rows` semantics for no-op updates. |
| `MULTI_STATEMENTS` | `multi_statements_enabled?` (db_statements.rb:37) | ✅ | Constant only (bit test on config flags). |
| `OPTION_MULTI_STATEMENTS_ON` / `_OFF` | `perform_query` batch (:43,:107) | ✅ | Constant defined; the **method** that consumes it is a no-op — see below. |

---

## 5. Gaps & required work (prioritized)

### P0 — blocks all Rails traffic
- [x] **`Client#abandon_results!`** — added as a no-op returning `nil`. `#query` already
      closes its statement (draining pending multi-results), so nothing lingers.

### P1 — connection options Rails passes and expects honored
Done (`Client#initialize`, verified with `prototype/options_smoke.rb`):
- [x] **`flags` handling** — accepts Integer bitmask **or** Array of String names
      (`@config[:flags]` may be either — mysql2_adapter:61). `MULTI_STATEMENTS` (bit
      `65536` / `"MULTI_STATEMENTS"`) enables multi-queries once at connect
      (`NativeSession.enableMultiQueries()`), so ActiveRecord's skip-toggle path works.
      Other capability flags are left to the driver.
- [x] **`FOUND_ROWS`** — already satisfied: Connector/J's default `useAffectedRows=false`
      sends `CLIENT_FOUND_ROWS`, so UPDATE returns matched rows as Rails expects. No action
      needed (we don't set `useAffectedRows`).
- [x] **timeouts**: `connect_timeout` → `connectTimeout`, `read_timeout` → `socketTimeout`
      (mysql2 seconds → JDBC ms). `read_timeout` is also stored and reported by the accessor.
      `write_timeout` has no JDBC analog → ignored.
- [x] **`init_command:`** — executed once after connect.
- [x] **`reconnect:`** → JDBC `autoReconnect=true`.
- [x] **`local_infile:`** → JDBC `allowLoadLocalInfile=true`.
- [x] Unknown AR config keys (`adapter`, `pool`, `checkout_timeout`, …) are tolerated.

Deferred:
- [ ] **SSL**: `ssl_mode` + `sslca/sslcert/sslkey/sslcapath/sslcipher` → Connector/J `sslMode`
      (DISABLED/PREFERRED/REQUIRED/VERIFY_CA/VERIFY_IDENTITY). Currently `useSSL=false` always.
- [ ] **`socket:`** → unix-socket connect. Connector/J 8+ has no built-in unix-socket support
      (needs a third-party socket factory); TCP is used regardless. Document/handle later.
- [ ] `default_file:` / `default_group:` — mysql option-file support (**unsupported** in JDBC;
      ignored rather than crashing).

### P2 — batch / multi-statement — **path validated, ready to implement**
`execute_batch` (mysql2/database_statements.rb:17) combines statements with `;\n` into one
string and drives the connection like this:

```
set_server_option(OPTION_MULTI_STATEMENTS_ON)   # toggle multi-statement mode ON
query("stmt1;\nstmt2;\n…")                        # one multi-statement call
abandon_results!                                  # discard results
set_server_option(OPTION_MULTI_STATEMENTS_OFF)   # toggle it back OFF (ensure block)
```

Today `set_server_option` is a `nil` no-op so the combined string is sent with
multi-statements disabled → **server** rejects it with a syntax error near the first `;`
(confirmed: the driver sends the string as-is; the rejection is server-side, there is no
client-side gate). This breaks:
- [ ] `begin_isolated_db_transaction` → `SET TRANSACTION ISOLATION LEVEL …; BEGIN`
      (abstract_mysql_adapter:234). Any `Model.transaction(isolation:)` fails.
- [ ] Fixture / seed loading (fixtures are inserted via `execute_batch`).

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

#### Empirically validated (see `prototype/MultiQueryProbe*.java`, live MySQL 8.0)
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
- [x] **`Client#query`** — unchanged; first result processed, rest drained on statement close.
- [x] Helper `nativeSession()` on `Client` — caches `conn.unwrap(ConnectionImpl.class).getSession()`.

Verified with `prototype/batch_smoke.rb` (JRuby, live MySQL): the full Rails pattern
(`set_server_option(ON)` → multi-statement `query` → `abandon_results!` →
`set_server_option(OFF)`) works, including the `SET TRANSACTION …; BEGIN` shape; unknown
option returns `false`; multi-statement is rejected when the toggle is OFF. Client-spec:
46 → 69 passing.

**Still out of scope (deferred multi-*client* API):** `next_result`, `store_result`,
`more_results?`, and the "commands out of sync" raise-on-outstanding-results behavior.
Rails does not use these — they back the remaining `client_spec` "Multiple results sets"
failures only.

#### Caveats to document, not blockers
- **Eager execution**: Connector/J executes the whole multi-statement packet before results
  are read, so mysql2's "error surfaces mid-iteration via `next_result`" semantics differ —
  an error in statement N raises from `execute()`, not from a later `more_results?`. Rails'
  `execute_batch` doesn't depend on the mid-iteration timing, so this is acceptable.
- **`affected_rows` after a batch** reflects only the first statement. Rails doesn't rely on
  it for batches (fixtures / isolated-transaction BEGIN).
- **`flags` including `MULTI_STATEMENTS`**: `multi_statements_enabled?` (db_statements.rb:31)
  makes Rails **skip** the toggle and assume the mode is always on. If a user sets that flag,
  we must enable multi-statements for the whole connection (call `enableMultiQueries()` once
  after connect, or set `allowMultiQueries=true`). Tie this to the P1 `flags` work.

### P3 — correctness polish
- [x] **`Error#error_number` returns the MySQL vendor code** — driver `getErrorCode()` passes
      the server errno through unmodified. Verified via `prototype/error_probe.rb` against live
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
      `CommunicationsException` wrapping `java.net.SocketTimeoutException`; `errorFromSQL`
      detects that in the cause chain and raises `Mysql2::Error::TimeoutError` (nil errno), so
      AR's `mysql2_adapter#translate_exception` maps it to `AdapterTimeout`. A genuine dropped
      connection (KILL, closed socket) has no `SocketTimeoutException` and still routes to
      `ConnectionError`. Confirmed by AR's own `test_read_timeout_exception`. Server-side
      statement timeouts (`ER_QUERY_TIMEOUT 3024`, `ER_QUERY_INTERRUPTED 1317`) come through as
      vendor codes and dispatch correctly.
- [ ] Confirm `server_info[:version]` parses via Rails' `version_string` regex
      `^(?:5\.5\.5-)?(\d+\.\d+\.\d+)` (MariaDB prefix). `mariadb?` keys off `full_version`
      containing "mariadb". (Deferred — needs a MariaDB server to exercise.)
- [ ] `warning_count` reflecting the **last** statement's warnings for `handle_warnings`
      (only used when `ActiveRecord.db_warnings_action` is set — off by default, low priority).

Known pre-existing (not P3): the invalid-UTF-8 error-message scrub uses `�` (U+FFFD) where
mysql2 emits `?` — one `error_spec` encoding example fails on this, unrelated to error codes.

---

## 6. What Rails does NOT require (scope relief)

- No `async` / `stream: true` from the adapter (results are fully buffered; `as: :array`).
- No `more_results?` / `next_result` / `store_result` — multi-result iteration is only via
  the batch path above, and even there Rails discards results (`abandon_results!`).
- No `thread_id`, `select_db` (Rails switches DB by reconnecting), `info`, `session_track`.
- `Result` only needs `fields`, `to_a`/`each` (as Array), `size`, `count`, `free`. No
  hash-mode, no `field_types`, no per-call `each(as:)` from the adapter itself.

---

## 7. Suggested implementation order

1. `abandon_results!` no-op (P0) — unblocks smoke-testing against a real Rails app.
2. `flags` parsing (Integer|Array) + `FOUND_ROWS`/`MULTI_STATEMENTS`→JDBC mapping (P1).
3. Timeouts + SSL + `init_command` connection options (P1).
4. Decide batch strategy and implement `execute_batch` support (P2).
5. Exception-code fidelity pass against `translate_exception` (P3).

Once P0–P1 land, the next concrete milestone is running ActiveRecord's own MySQL test
suite (`activerecord/test` with `ARCONN=mysql2`) and triaging from there.

---

## 8. Running ActiveRecord's own test suite (JRuby)

Rails' `activerecord/test` files boot on our gem — the stock `Mysql2Adapter` drives our
`Mysql2::Client` unchanged. The harness (kept under `prototype/ar/`, git-ignored):

- **Bundle**: Rails' own `Gemfile` excludes `mysql2`/`pg`/`trilogy` on JRuby and its lockfile
  lacks the `java` platform, so it can't drive these tests directly. Instead we reuse
  **activerecord-jdbc-adapter's** `Gemfile` (`../activerecord-jdbc-adapter`), which pulls Rails
  from the local clone via `ENV['RAILS']` and resolves the test-support gems (minitest 6,
  mocha, minitest-excludes, zeitwerk, railties, …) on JRuby. `bundle install` into a writable
  `BUNDLE_PATH` (needs network once).
- **Adapter on our gem**: run with `-rbundler/setup -r prototype/ar/bundled_harness.rb`, which
  prepends our `lib` and stubs the adapter's `gem "mysql2"` activation (our gem is required
  from source, not installed). Model after arjdbc's `rakelib/rails.rake` `rails:test_mysql`.
- **Config / env**: `ARCONN=mysql2`, `ARCONFIG=prototype/ar/config.yml` (points at
  127.0.0.1:13306), `N=0` (minitest parallel crashes on JRuby), `CLASSPATH=<connector-j jar>`.
  Databases `activerecord_unittest` / `activerecord_unittest2` + a `rails` user must exist.
- Runner: `prototype/ar/run_test.sh <test/cases/.../foo_test.rb>`.

**Results — mysql2-specific adapter tests (Rails 8.0.5, MySQL 8.0): 55/55 passing.**

| test file | result |
|---|---|
| `adapters/mysql2/mysql2_adapter_test.rb` | 24 runs, 64 assertions, **0 failures** |
| `adapters/mysql2/check_constraint_quoting_test.rb` | 1 run, **0 failures** |
| `adapters/mysql2/mysql2_rake_test.rb` | 26 runs, **0 failures** |
| `adapters/mysql2/dbconsole_test.rb` | 4 runs, **0 failures** |

`test_read_timeout_exception` drove the P3 `TimeoutError` fix above.

**Results — broader `test/cases/**` slice (Rails 8.0.5, MySQL 8.0):**

| test file | runs | outcome |
|---|---:|---|
| `adapter_test.rb` | 72 | all pass |
| `quoting_test.rb` | 37 | all pass |
| `calculations_test.rb` | 232 | all pass |
| `persistence_test.rb` | 158 | all pass |
| `finder_test.rb` | 263 | 261 pass; 2 unrelated¹ |
| `migration_test.rb` | 84 | all pass |
| `schema_dumper_test.rb` | 43 | all pass; 1 unrelated² |
| `attribute_methods_test.rb` | 135 | all pass |
| `binary_test.rb` | 3 | all pass (ASCII-8BIT round-trip) |
| `date_time_precision_test.rb` | 11 | all pass (fractional-second DATETIME) |
| `time_precision_test.rb` | 6 | all pass (TIME precision) |
| `transactions_test.rb` | 104 | all pass (nested tx, savepoints, rollback) |
| `transaction_isolation_test.rb` | 6 | all pass (exercises the P2 `SET TRANSACTION …; BEGIN` batch path) |
| `transaction_callbacks_test.rb` | 57 | all pass |
| `transaction_instrumentation_test.rb` | 21 | all pass |

~1232 runs; **zero failures attributable to this gem**. The only non-passes:
1. `finder_test`: `test_exists_with_large_number` / `test_find_by_bang_..._large_number` —
   `ActiveModel::Type::Integer#ensure_in_range` raises `ActiveModel::RangeError` for values
   past int8 during query-attribute serialization/`hash`, before the value reaches the
   driver. An ActiveModel + JRuby integer-handling edge, adapter-independent.
2. `schema_dumper_test`: one test hard-codes the `sqlite3` adapter, which isn't in this
   bundle (arjdbc's harness adds `jdbc-sqlite3` for exactly these; not wired here).

Data-type round-tripping (binary, DECIMAL, DATE/TIME/DATETIME with precision) — the heart of
the byte-caster — passes cleanly through the real adapter. The transaction-isolation tests
exercise the P2 multi-statement batch path (`SET TRANSACTION …; BEGIN` via
`enableMultiQueries()`) end-to-end.

**Prepared-statement path** (`prepared_statements: true`, `prototype/ar/config_prepared.yml`):
Rails' default for mysql2 is unprepared, but the prepared path
(`raw_connection.prepare(sql)` → `stmt.execute(*binds)` → `stmt.affected_rows` → `stmt.close`,
backed by our client-side JDBC `PreparedStatement` reusing the text-protocol byte caster)
works: `persistence_test` **158/158** and `finder_test` 261/263 (same two adapter-independent
int-range edges as unprepared), `prepared_statement_status_test` 1/1.

**JSON / enum column types:**
- `adapters/abstract_mysql_adapter/json_test.rb` (native MySQL `JSON` column, incl.
  `JSONSharedTestCases`) — **25/25**. Validates the byte-caster on `JSON` columns end-to-end.
- `json_attribute_test.rb` — **25/25**.
- `enum_test.rb` — 95/97. The two failures assert a specific enum-scope-clash *warning string*
  appears in the captured log; our harness routes `ActiveRecord::Base.logger` to `debug.log`
  (per AR's `connect`), so the capture is empty. Log-assertion only, adapter-independent; all
  enum data operations pass.

Next candidates: associations, and (lower priority) SSL/socket connection options.
