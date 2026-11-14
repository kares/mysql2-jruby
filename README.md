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
analog), `:init_command`, `:reconnect`, `:local_infile`, `:flags` (Integer bitmask or Array of flag names,
e.g. `["MULTI_STATEMENTS"]`), plus two JDBC escape hatches: `:url` (used verbatim, overriding
host/port/database) and `:properties` (a Hash passed to the driver).

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

Everything below is a deliberate gap, not a bug - C client either exposes things 
a JDBC connection has no equivalent for, or the feature was deferred.

**Not Implemented**

- **Async / EventMachine** — no `Mysql2::EM`; the `:async` query option is accepted (it is in
  `default_query_options`) but ignored, queries are always synchronous.
- **Streaming** (`stream: true`) — results are always buffered client-side and re-iterable,
  so single-pass semantics don't apply.
- **Multi-result iteration** — `more_results?`, `next_result` and `store_result` are missing.
  Multi-statement queries work (`:flags => [MULTI_STATEMENTS]` / `set_server_option`), but only
  the first result set is returned; the rest are drained. `abandon_results!` is implemented,
  which is what ActiveRecord's batch path uses.
- **`#socket`** — there is no file descriptor behind a JDBC connection.
- **Unix sockets** (`:socket`) — Connector/J is TCP only; the option is ignored.
- **libmysqlclient config files** — `:default_file`, `:default_group`, `:secure_auth`,
  `:connect_attrs` have no Connector/J counterpart.
- **`#automatic_close`** — a no-op stub. There is no `fork`, and sockets are owned by the JVM.

**Behaves differently**

- **`:flags` is read, not passed through.** Only `FOUND_ROWS`, `MULTI_STATEMENTS` and
  `LOCAL_FILES` change behaviour; the rest of the bitmask is accepted and ignored (the
  handshake capabilities are Connector/J's). `Mysql2::Client` still defines the flag
  constants (`FOUND_ROWS`, `MULTI_STATEMENTS`, `SSL_MODE_*`, …) so option hashes carried
  over from the C gem keep resolving.
- **Prepared statements are client-side.** Connector/J interpolates binds by default
  (`useServerPrepStmts=false`), so the server's `Prepared_stmt_count` doesn't move and a bound
  `Time`/`DateTime` comes back as `VAR_STRING` rather than a typed `DATETIME`. Real typed
  columns — what ActiveRecord reads — are unaffected. Pass
  `properties: { useServerPrepStmts: "true" }` to switch.
- **A query in flight can't be interrupted.** JRuby can't preempt a blocking JDBC socket read,
  so `Timeout`/`Thread#raise` only fires once the query completes and the connection stays
  healthy rather than being torn down. Use `:read_timeout` (JDBC `socketTimeout`) to bound a query.
- **`Mysql2::Error` messages and SQLSTATEs are Connector/J's.** `#error_number` is the real
  MySQL vendor errno, but the text differs — e.g. a killed connection reports
  "Communications link failure" (`08S01`), not "Lost connection to MySQL server".
- **Result server flags** — `query_was_slow` / `no_index_used` are not surfaced by JDBC.
- **No concurrent-use guard.** The C client raises when two threads use one connection; here
  the behaviour is Connector/J's. Don't share a `Mysql2::Client` across threads — use a pool
  (ActiveRecord already does).
- **SSL options are Java keystores.** `:sslkey`/`:sslcert`/`:sslca` turn TLS on and are passed
  to Connector/J as keystore URLs, *not* PEM files (as in activerecord-jdbc-adapter).
  `:ssl_mode` is not read — the `SSL_MODE_*` constants exist, but pass
  `properties: { sslMode: "VERIFY_IDENTITY" }` to set it.

## License

This gem's Ruby and Java code is licensed under the [MIT License](LICENSE). 
Its source is included in the gem.

MySQL Connector/J is a separate runtime dependency and is not bundled with this gem.
Oracle distributes its Community edition under GPLv2 with the
[Universal FOSS Exception v1.0](https://oss.oracle.com/licenses/universal-foss-exception/).
The exception permits linking with open-source software distributed with complete
corresponding source under an OSI-approved license, including MIT, while each part
retains its own license. Connector/J remains subject to its own license and notices.
