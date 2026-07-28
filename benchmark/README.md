# Performance suite

Compares **CRuby + mysql2** (the C extension) against **JRuby + this gem** (Connector/J
over JDBC with raw text-protocol casting). Both expose the same `Mysql2::Client` API,
so the same scenarios run unchanged on each engine.

Two suites:

| script | what it measures |
|--------|------------------|
| `types.rb`   | one scenario per MySQL column type — the per-value decoding cost |
| `run.rb`     | a mix of whole-query shapes (`cast: false`, `as: :array`, `escape`, …) |
| `compare.rb` | runs one of the above under both engines via `mise` and diffs them |

[RESULTS.md](RESULTS.md) has a recorded run of the per-type suite.

## Prerequisites

- A MySQL server matching `spec/configuration.yml`, or `MYSQL_HOST`/`MYSQL_PORT`/
  `MYSQL_USER`/`MYSQL_PASSWORD`/`MYSQL_DATABASE` when that (git-ignored) file is
  absent — default `127.0.0.1:13306`, db `test`.
- JRuby side: `rake compile` has built `lib/mysql2.jar`.
- CRuby side: the `mysql2` gem installed for the CRuby that `mise` will use:

  ```sh
  mise exec ruby@3.4.8 -- gem install mysql2
  ```

  On Fedora that needs the client headers (`sudo dnf install mariadb-connector-c-devel`),
  or point the build at an existing install with
  `-- --with-mysql-config=/path/to/mariadb_config`.

The table (`mysql2_bench`) is created, migrated and seeded automatically on each run.

## Running

```sh
# side-by-side comparison (this is the interesting one)
rake bench:compare                  # per-type suite
rake bench:compare BENCH_SCRIPT=run.rb

# single engine
rake bench                          # mixed scenarios, JRuby + this gem
rake bench:types                    # per-type,        JRuby + this gem
mise exec ruby@3.4.8 -- ruby benchmark/types.rb    # CRuby + the C extension
```

`rake bench*` launches JRuby with the connector jar on the JVM classpath. To run by
hand under JRuby, set it yourself:
`CLASSPATH=vendor/mysql-connector-j.jar jruby benchmark/types.rb`.

## Knobs (env vars)

| var | default | meaning |
|-----|---------|---------|
| `BENCH_ROWS`     | `1000` | rows seeded into the table |
| `BENCH_WARMUP`   | `3`    | warmup seconds per scenario (drives JRuby's JIT) |
| `BENCH_TIME`     | `5`    | measured seconds per scenario |
| `BENCH_SETUP`    | –      | set to `0` to skip re-seeding an already-populated table |
| `BENCH_LIMIT`    | `100`  | rows per query (`types.rb`) |
| `BENCH_COPIES`   | `8`    | aliases of the column per row (`types.rb`) |
| `BENCH_PREPARED` | –      | set to `true` to go through `Client#prepare` (`types.rb`) |
| `BENCH_MRI`      | `3.4.8`| mise ruby for the C-extension side (`compare.rb`) |
| `BENCH_JRUBY`    | `mise.toml` | mise ruby for this gem (`compare.rb`) |
| `BENCH_CPUS`     | –      | `taskset -c` mask pinning both engines to the same cores (`compare.rb`) |
| `BENCH_FORMAT`   | –      | `json` to emit machine-readable results |
| `BENCH_OUT`      | –      | file to write the JSON to instead of stdout |

Give JRuby a real warmup (`BENCH_WARMUP=5` or more) for representative numbers — the
first thousand iterations run interpreted before the JIT kicks in.

## Getting stable numbers

The two engines run sequentially, minutes apart, so anything that makes the machine
slower over time biases the ratio rather than cancelling out.

- **Pin the cores** on a hybrid CPU. `lscpu -e=CPU,MAXMHZ` (or
  `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`) shows two clock tiers; pin
  both engines to the fast tier, and give the JVM several cores — it needs threads for
  JIT and GC, so a single-core mask penalises JRuby specifically:

  ```sh
  BENCH_CPUS=0-3,12-15 rake bench:compare
  ```

- **Fix the clock** (needs root), otherwise scaling and thermal drift move between the
  two legs:

  ```sh
  sudo cpupower frequency-set -g performance
  echo performance | sudo tee /sys/devices/system/cpu/cpu*/cpufreq/energy_performance_preference
  ```

- Keep the machine otherwise idle, and treat differences under ~10% as noise.

## How the per-type suite isolates a type

Each scenario selects the same column aliased `BENCH_COPIES` times over `BENCH_LIMIT`
rows, so 800 values are decoded per query and the per-value cost dominates the fixed
per-query/per-row overhead. That overhead is measured on its own by the `id (1 col)`
scenario, whose `ns/value` is really `ns/row`.

`compare.rb` also reports the Ruby class each side decodes the type to. A `<-- differs`
marker means the two engines are not doing comparable work for that type, and the ratio
should not be read as a speedup.

`BENCH_PREPARED=true` is not an apples-to-apples protocol comparison: CRuby's mysql2
issues a real server-side prepare (binary protocol), while this gem uses Connector/J's
client-side prepares (text protocol). It compares what each gem actually does for
`Client#prepare`, not the same wire path.
