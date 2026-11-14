# Mysql2 (JRuby)

A JRuby-native implementation of [mysql2](https://github.com/brianmario/mysql2).
Instead of *libmysqlclient* this gem talks to MySQL through the **MySQL Connector/J** driver.

The goal here is to run Rails's stock `mysql2` adapter unmodified by re-implementing 
the API required by `ActiveRecord`'s adapter (`Mysql2::Client`, `Mysql2::Result`, 
`Mysql2::Statement` and `Mysql2::Field`).

## Status

Tested against JRuby 10.0 and MySQL 8.0 / 8.4 (see [`.github/workflows/test.yml`](.github/workflows/test.yml)).

| suite | result |
| --- | --- |
| Rails' own ActiveRecord suite (8.0.5, mysql2 adapter) | 8649 runs, 28119 assertions, **0 failures**, 92 excluded |
| upstream mysql2 RSpec suite | 347 examples, **0 failures**, 65 excluded |
| in-house minitest checks | 54 runs, 129 assertions, **0 failures** |

CI additionally runs the ActiveRecord suite against Rails 7.2.

That is **98.9%** of ActiveRecord's suite green, with nothing left failing. 
The 92 excluded tests (`test/rails/excludes/mysql2/`, each carrying a note on why) 
are not adapter gaps: 58 load the `sqlite3` C extension, which can't run on JRuby 
and the rest are (minor) JRuby bugs - `Marshal` round-trips, `Array#-` calling `#hash`, 
SnakeYAML's document size limit, msgpack-java, thread-scheduling races.

Performance against CRuby + the C `mysql2` gem is tracked in
[`benchmark/RESULTS.md`](benchmark/RESULTS.md) — the short version is roughly **1.5x** on
raw driver throughput and **1.4x** through ActiveRecord (geometric mean, JRuby 10 vs Ruby 3.4.8).

## Setup

To be able to run `ActiveRecord` (`mysql2_adapter`) unmodified the gem is released 
under the **same name** (as a *-java* variant) in a [gem.coop](https://gem.coop/) 
namespace, so the `Gemfile` just needs:

```ruby
source "https://rubygems.org"

ruby ">= 3.2.0"

gem "rails", "~> 8.0.0"

# mysql2 as the database adapter for Active Record
gem "mysql2", "~> 0.5", platform: :jruby, source: 'https://gem.coop/@kares'
```
<!--
If the gem is installed under its own name instead (`mysql2-jruby`), require
`mysql2/compat` before ActiveRecord loads the adapter — it registers a `mysql2`
alias so the adapter's `gem "mysql2", "~> 0.5"` resolves:

```ruby
gem "mysql2-jruby", platform: :jruby, require: "mysql2/compat"
```
-->

### Connector/J

The driver jar is **not** bundled with the gem; you will need to put MySQL Connector/J 
(>= 8.2, 9.4 tested) on the JVM classpath, or point `RUBY_MYSQL2_CONNECTOR_JAR` at it:

```sh
export RUBY_MYSQL2_CONNECTOR_JAR=/path/to/mysql-connector-j-9.4.0.jar
```

## Usage

```ruby
require 'mysql2'
client = Mysql2::Client.new(host: '127.0.0.1', port: 3306,
                            username: 'root', password: 'root', database: 'test')
client.query("SELECT id, name, created_at FROM users").each do |row|
  row['id']         # => Integer
  row['name']       # => String (UTF-8)
  row['created_at'] # => Time
end
```

Connection options: `:host`/`:hostname`, `:port`, `:username`, `:password`, `:database`/`:dbname`/`:db`,
`:encoding`, `:collation`, `:connect_timeout`, `:read_timeout`, `:write_timeout` (validated, no JDBC
analog), `:init_command`, `:reconnect`, `:local_infile`, `:get_server_public_key`, `:server_public_key_path`,
`:default_auth`, `:ssl_mode` (`:disabled`, `:preferred`, `:required`, `:verify_ca`, `:verify_identity` or
`SSL_MODE_*` constants), `:sslca`, `:sslcapath`, `:sslcert`, `:sslkey`, `:sslcipher`, `:sslverify`,
`:flags` (Integer bitmask, Array, or space-separated String of flag names, e.g. `["MULTI_STATEMENTS"]`),
plus two JDBC escape hatches: `:url` (used verbatim, overriding host/port/database) and `:properties`
(a Hash passed to the driver).

Query options are mysql2's and behave the same: `:as`, `:symbolize_keys`, `:cast`, `:cast_booleans`,
`:database_timezone`, `:application_timezone`, `:cache_rows`.

## Build & test

```sh
jruby -S rake connector   # download Connector/J -> vendor/ (or set RUBY_MYSQL2_CONNECTOR_JAR)
jruby -S rake compile     # -> lib/mysql2.jar
jruby -S rake test        # in-house minitest sanity checks (creates the `test` DB)
```

The upstream mysql2 spec suite runs out of the `./mysql2` submodule (or `MYSQL2=path`),
with this gem swapped in for the C extension:

```sh
git submodule update --init mysql2
jruby -S rake mysql2:db:build
jruby -S rake mysql2:spec  # SPEC=spec/mysql2/result_spec.rb to limit
```

Rails' own `ActiveRecord` suite runs against a Rails checkout:

```sh
RAILS=../rails jruby -S rake rails:bundle    # once, installs the suite's test deps
RAILS=../rails jruby -S rake rails:db:build  # creates activerecord_unittest{,2}
RAILS=../rails jruby -S rake rails:test      # full suite; TEST=test/cases/... to limit
```

Per-test skips live in `test/rails/excludes/mysql2/`, each with a note on why —
they are JRuby core issues (Marshal, `Array#-`, SnakeYAML limits), not adapter gaps.
`NO_EXCLUDES=true` runs without them.

## Limitations

Because this gem runs on top of JDBC rather than *libmysqlclient*, some features from 
the C `mysql2` gem either have no JDBC equivalent or behave (slightly) differently.

### Not supported

- **Async / EventMachine** — `Mysql2::EM` is not provided. The `:async` query option is accepted
  for compatibility, but queries always run synchronously.
- **Streaming** (`stream: true`) — result sets are buffered in memory and can be iterated multiple
  times; single-pass streaming is not implemented yet.
- **Iterating multiple result sets** — `more_results?`, `next_result`, and `store_result` are not
  implemented. Multi-statement queries work (`flags: ["MULTI_STATEMENTS"]` or `set_server_option`),
  returning the first result set and draining the rest (`abandon_results!` is supported for
  ActiveRecord's batch execution).
- **Unix domain sockets** (`:socket`) and **`#socket`** — Connector/J connects over TCP/IP by
  default and does not expose an underlying OS file descriptor.
- **libmysqlclient option files** — `:default_file`, `:default_group`, `:secure_auth`, and
  `:connect_attrs` are specific to *libmysqlclient* and ignored here.
- **`#automatic_close`** — kept as a no-op stub since JVM does not support `fork`.

### Behavioral differences

- **`:flags` are mapped to driver properties.** Common flags (`FOUND_ROWS`, `MULTI_STATEMENTS`,
  `LOCAL_FILES`, `SSL`, `SSL_VERIFY_SERVER_CERT`, `COMPRESS`, `INTERACTIVE`) are translated into
  their Connector/J equivalents. Other bitmask flags are accepted and ignored, and all
  `Mysql2::Client` flag constants remain defined so existing configs work without changes.
- **Client-side prepared statements by default.** Connector/J *emulates* prepared statements on 
  the client (`useServerPrepStmts=false`), so `Prepared_stmt_count` on the server does not 
  increment; bound `Time`/`DateTime` parameters return as `VAR_STRING` unless cast from a typed 
  table column. You can opt into server-side prepared statements with
  `properties: { useServerPrepStmts: "true" }`.
- **In-flight queries cannot be interrupted by `Thread#raise` / `Timeout.timeout`.** 
  JRuby cannot preempt a blocking JDBC socket read, so Ruby timeouts only fire after the query 
  finishes. Use `:read_timeout` (mapped to JDBC `socketTimeout`) to bound query execution time.
- **Error messages and SQLSTATEs come from Connector/J.** `Mysql2::Error#error_number` still
  reports the MySQL server error code, but client-side failures (such as a dropped connection)
  use Connector/J's wording and SQLSTATE (e.g. `"Communications link failure"` / `08S01`).
- **Server status flags on results** — `query_was_slow` and `no_index_used` are not supported.
- **Thread safety** — unlike the C extension, which raises immediately on concurrent access, thread
  behavior follows Connector/J. Avoid sharing a single `Mysql2::Client` across threads; use a
  connection pool (as ActiveRecord does).
- **SSL accepts both PEM files and Java keystores.** `:sslca`, `:sslcapath`, `:sslcert`, and
  `:sslkey` work with standard PEM files as well as `.p12`/`.jks` keystores (compatible with
  `activerecord-jdbc-adapter`). When using `:verify_ca` or `:verify_identity` without `:sslca`,
  Connector/J verifies against the JVM's default system truststore (`cacerts`).
- **Public key retrieval (`caching_sha2_password`).** To allow local TCP connections to MySQL 8+
  out of the box when SSL is off, `allowPublicKeyRetrieval` defaults to `true`. On untrusted
  networks without SSL, pass `get_server_public_key: false` (and optionally
  `server_public_key_path: "/path/to/public_key.pem"`).

## License

This gem's Ruby and Java code is licensed under the [MIT License](LICENSE). 
Its source is included in the gem.

MySQL Connector/J is a separate runtime dependency and is not bundled with this gem.
Oracle distributes its Community edition under GPLv2 with the
[Universal FOSS Exception v1.0](https://oss.oracle.com/licenses/universal-foss-exception/).
The exception permits linking with open-source software distributed with complete
corresponding source under an OSI-approved license, including MIT, while each part
retains its own license. Connector/J remains subject to its own license and notices.
