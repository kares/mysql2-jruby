# Benchmark results

CRuby + the `mysql2` C extension vs JRuby + this gem. Produced by
`benchmark/compare.rb` (see [README.md](README.md) for methodology and reproduction).
Three suites:

- [driver (`types.rb`)](#per-type-results) — decoding each MySQL column type
- [mixed (`run.rb`)](#mixed-query-results) — whole-query shapes (`cast: false`, `as: :array`, `escape`, …)
- [ActiveRecord (`active_record.rb`)](#activerecord-results) — the same comparison
  one layer up

This file records the **2026-11-17** runs (CRuby `4.0.1` vs JRuby `10.1.3.0` and
Rails `8.1.4.1`, both **with** and **without** CRuby YJIT / OpenJDK 26) alongside
the earlier **2026-07-28** (`c1f97db`) baseline (CRuby `3.4.8` vs JRuby `10.0.6.0`
and Rails `8.0.5`).

```sh
# 2026-11-17 JIT-to-JIT command:
RUBY_YJIT_ENABLE=1 JAVA_HOME=/opt/java/jdk-26 JRUBY_OPTS="-Xcompile.invokedynamic=true" \
  BENCH_MRI=4.0.1 BENCH_JRUBY=jruby-10.1.3.0 BENCH_CPUS=0-3,12-15 \
  BENCH_WARMUP=10 BENCH_TIME=5 BENCH_ROWS=1000 rake bench:compare
```

## Overall summary

| run | suite | geometric mean | range | < 1.00x |
|---|---|---:|---|---:|
| **2026-11-17 (`4.0.1 +YJIT` vs `JRuby 10.1.3.0` + JDK 26 `+indy`)** | `types.rb` (26) | **1.36x** | 0.90x - 3.44x | 6 |
| | `run.rb` (10) | **1.40x** | 0.94x - 2.51x | 1 |
| | `active_record.rb` 8.1.4.1 (11) | **0.97x** | 0.75x - 1.20x | 6 |
| **2026-11-17 (`4.0.1` no YJIT vs `JRuby 10.1.3.0` + JDK 21 `+indy`)** | `types.rb` (26) | **1.34x** | 0.81x - 3.32x | 5 |
| | `run.rb` (10) | **1.55x** | 1.01x - 2.41x | 0 |
| | `active_record.rb` 8.1.4.1 (11) | **1.34x** | 1.01x - 1.62x | 0 |
| **2026-07-28 (`3.4.8` no YJIT vs `JRuby 10.0.6.0` + JDK 21)** | `types.rb` (26) | **1.48x** | 0.98x - 3.75x | 1 |
| | `active_record.rb` 8.0.5 (11, `@60s`) | **1.41x** | 1.11x - 1.75x | 0 |

At the driver level (`types.rb` and `run.rb`), YJIT does not speed up `mysql2`'s C
extension, while OpenJDK 26 boosts JRuby (`id (1 col)` rises from `9059 -> 12698`
ops/sec), keeping JRuby ahead **1.36x** (`types.rb`) and **1.40x** (`run.rb`) even
against CRuby `+YJIT`. Inside ActiveRecord 8.1, YJIT accelerates Rails' Ruby
dispatch significantly: JRuby still leads all 5 bulk-materialization scenarios
(`1.10x - 1.20x`), while CRuby `+YJIT` takes single-row / dispatch-heavy queries
(`0.75x - 0.96x`), compared to **1.34x** across all 11 scenarios when YJIT is off.

## Environment

Hardware and OS controls are identical across all recorded runs:

- **MySQL**: `8.0.46` in a container on `127.0.0.1:13306`
- **CPU**: AMD Ryzen AI 9 HX PRO 370 (4x Zen5 @ 5157 MHz + 8x Zen5c @ 3289 MHz)
- **Governor**: `performance` + EPP `performance`, boost on
- **Pinning**: both engines `taskset -c 0-3,12-15` (the Zen5 cores)

| run | date | revision | CRuby | JRuby & JVM | Connector/J | ActiveRecord | OS / kernel |
|---|---|---|---|---|---|---|---|
| **2026-11-17 (YJIT / JDK 26)** | 2026-11-17 | `b239334` | `4.0.1` (`+YJIT +PRISM`), `mysql2` `0.5.7` (`libmariadb` `3.4.9`) | `10.1.3.0` (`+indy +jit`), `0.5.7.0.RC1`, OpenJDK `26+35-2893` | `8.4.0` | `8.1.4.1` (`40s` warmup) | Fedora 44, `7.2.15` |
| **2026-11-17 (no YJIT / JDK 21)** | 2026-11-17 | `b239334` | `4.0.1` (`+PRISM`), `mysql2` `0.5.7` (`libmariadb` `3.4.9`) | `10.1.3.0` (`+indy +jit`), `0.5.7.0.RC1`, OpenJDK `21.0.11` | `8.4.0` | `8.1.4.1` (`40s` warmup) | Fedora 44, `7.2.15` |
| **2026-07-28 baseline** | 2026-07-28 | `c1f97db` | `3.4.8`, `mysql2` `0.5.7` (`libmariadb` `3.4.9`) | `10.0.6.0`, `0.5.7.0`, OpenJDK `21.0.11` | `9.4.0` | `8.0.5` (`60s` warmup) | Fedora 44, `7.1.5` |

## Per-type results

100 rows x 8 aliases of one column per query, `BENCH_WARMUP=10`, `BENCH_TIME=5`,
`BENCH_ROWS=1000`. Ratio is JRuby / CRuby; `> 1.00x` means this gem is faster.
`ns/value` is the per-column-value decode cost, the more meaningful figure —
`ops/sec` counts whole queries and so carries the fixed per-query overhead.

Every type decodes to the same Ruby class on both engines, so each row compares
like with like. `compare.rb` flags any type where that stops being true.

### 2026-11-17: CRuby 4.0.1 (+YJIT) vs JRuby 10.1.3.0 (OpenJDK 26, +indy)

| scenario | CRuby ops/sec | CRuby ns/value | JRuby ops/sec | JRuby ns/value | ratio | decodes to |
|---|---:|---:|---:|---:|---:|---|
| time       |  1842.6 | 678.4 |  6346.8 | 196.9 | **3.44x** | Time |
| date_time  |  1875.1 | 666.6 |  6055.9 | 206.4 | **3.23x** | Time |
| timestamp  |  1816.9 | 688.0 |  5483.7 | 227.9 | **3.02x** | Time |
| date       |  4170.9 | 299.7 | 10337.8 | 120.9 | **2.48x** | Date |
| decimal    |  3704.4 | 337.4 |  7420.5 | 168.5 | **2.00x** | BigDecimal |
| text       |  5898.9 | 211.9 |  9978.5 | 125.3 | 1.69x | String |
| varchar    |  7832.5 | 159.6 | 11863.7 | 105.4 | 1.51x | String |
| enum       |  8519.6 | 146.7 | 11476.9 | 108.9 | 1.35x | String |
| char       |  8355.8 | 149.6 | 11229.6 | 111.3 | 1.34x | String |
| json       |  3331.2 | 375.2 |  4369.5 | 286.1 | 1.31x | String |
| set        |  6815.2 | 183.4 |  8851.9 | 141.2 | 1.30x | String |
| varbinary  |  9133.0 | 136.9 | 11679.6 | 107.0 | 1.28x | String |
| null       | 10191.8 | 122.6 | 12609.7 |  99.1 | 1.24x | String |
| binary     |  9170.0 | 136.3 | 11084.6 | 112.8 | 1.21x | String |
| bit        |  9717.4 | 128.6 | 11621.4 | 107.6 | 1.20x | String |
| blob       |  8114.9 | 154.0 |  9140.6 | 136.8 | 1.13x | String |
| double     |  3869.9 | 323.0 |  4117.8 | 303.6 | 1.06x | Float |
| float      |  7196.1 | 173.7 |  7557.9 | 165.4 | 1.05x | Float |
| tiny_int   | 10601.6 | 117.9 | 10888.9 | 114.8 | 1.03x | Integer |
| bool       | 11558.3 | 108.1 | 11249.2 | 111.1 | 0.97x | Integer |
| medium_int | 11582.3 | 107.9 | 11139.6 | 112.2 | 0.96x | Integer |
| year       | 10936.7 | 114.3 | 10307.3 | 121.3 | 0.94x | Integer |
| int        | 11205.8 | 111.5 | 10525.2 | 118.8 | 0.94x | Integer |
| small_int  | 11260.1 | 111.0 | 10343.3 | 120.9 | 0.92x | Integer |
| big_int    | 10751.7 | 116.3 |  9709.7 | 128.7 | 0.90x | Integer |
| | | | | | | |
| `id` (1 col, row overhead) | 11741.7 | 851.7 | 12698.2 | 787.5 | 1.08x | |
| **geometric mean** | | | | | **1.36x** | |

### 2026-11-17: CRuby 4.0.1 (no YJIT) vs JRuby 10.1.3.0 (OpenJDK 21, +indy)

| scenario | CRuby ops/sec | CRuby ns/value | JRuby ops/sec | JRuby ns/value | ratio | decodes to |
|---|---:|---:|---:|---:|---:|---|
| timestamp  |  1698.2 | 736.1 |  5635.1 | 221.8 | **3.32x** | Time |
| time       |  1835.2 | 681.1 |  6079.6 | 205.6 | **3.31x** | Time |
| date_time  |  1769.6 | 706.4 |  5746.4 | 217.5 | **3.25x** | Time |
| date       |  3738.2 | 334.4 |  8485.9 | 147.3 | **2.27x** | Date |
| decimal    |  3360.6 | 372.0 |  5990.2 | 208.7 | **1.78x** | BigDecimal |
| enum       |  6752.5 | 185.1 | 10989.6 | 113.7 | 1.63x | String |
| text       |  5826.4 | 214.5 |  9054.8 | 138.0 | 1.55x | String |
| varchar    |  6413.0 | 194.9 |  9651.2 | 129.5 | 1.50x | String |
| varbinary  |  7393.8 | 169.1 | 11024.0 | 113.4 | 1.49x | String |
| char       |  6643.3 | 188.2 |  9455.7 | 132.2 | 1.42x | String |
| set        |  6585.3 | 189.8 |  8464.3 | 147.7 | 1.29x | String |
| bit        |  7729.2 | 161.7 |  9057.5 | 138.0 | 1.17x | String |
| null       |  8366.0 | 149.4 |  9716.9 | 128.6 | 1.16x | String |
| binary     |  7674.6 | 162.9 |  8445.9 | 148.0 | 1.10x | String |
| double     |  2925.5 | 427.3 |  3203.7 | 390.2 | 1.10x | Float |
| bool       |  8779.9 | 142.4 |  9444.9 | 132.3 | 1.08x | Integer |
| json       |  3440.1 | 363.4 |  3583.8 | 348.8 | 1.04x | String |
| float      |  5546.0 | 225.4 |  5763.7 | 216.9 | 1.04x | Float |
| int        |  8620.0 | 145.0 |  8905.4 | 140.4 | 1.03x | Integer |
| year       |  8169.8 | 153.0 |  8268.7 | 151.2 | 1.01x | Integer |
| tiny_int   |  8898.4 | 140.5 |  8767.8 | 142.6 | 0.99x | Integer |
| blob       |  7948.7 | 157.3 |  7790.6 | 160.4 | 0.98x | String |
| small_int  |  8611.0 | 145.2 |  8302.3 | 150.6 | 0.96x | Integer |
| big_int    |  8342.6 | 149.8 |  7905.1 | 158.1 | 0.95x | Integer |
| medium_int | 11003.6 | 113.6 |  8933.7 | 139.9 | 0.81x | Integer |
| | | | | | | |
| `id` (1 col, row overhead) | 9002.9 | 1110.8 | 9059.0 | 1103.9 | 1.01x | |
| **geometric mean** | | | | | **1.34x** | |

### 2026-07-28 (`c1f97db`): CRuby 3.4.8 vs JRuby 10.0.6.0 (OpenJDK 21, Connector/J 9.4.0)

| scenario | CRuby ops/sec | CRuby ns/value | JRuby ops/sec | JRuby ns/value | ratio | decodes to |
|---|---:|---:|---:|---:|---:|---|
| date_time  |  1681.7 | 743.3 |  6305.0 | 198.3 | **3.75x** | Time |
| time       |  1797.4 | 695.4 |  6388.4 | 195.7 | **3.55x** | Time |
| timestamp  |  1624.8 | 769.3 |  5556.1 | 225.0 | **3.42x** | Time |
| date       |  3633.8 | 344.0 | 10602.9 | 117.9 | **2.92x** | Date |
| decimal    |  3550.9 | 352.0 |  7098.5 | 176.1 | **2.00x** | BigDecimal |
| varchar    |  6623.7 | 188.7 | 11979.8 | 104.3 | 1.81x | String |
| enum       |  7232.3 | 172.8 | 11614.1 | 107.6 | 1.61x | String |
| varbinary  |  7300.1 | 171.2 | 11623.5 | 107.5 | 1.59x | String |
| char       |  7168.0 | 174.4 | 11141.0 | 112.2 | 1.55x | String |
| binary     |  7304.9 | 171.1 | 10996.5 | 113.7 | 1.51x | String |
| set        |  5984.5 | 208.9 |  8781.8 | 142.3 | 1.47x | String |
| text       |  6329.6 | 197.5 |  9161.7 | 136.4 | 1.45x | String |
| bit        |  8124.4 | 153.9 | 11349.7 | 110.1 | 1.40x | String |
| null       |  9042.5 | 138.2 | 12702.7 |  98.4 | 1.40x | String |
| blob       |  6638.5 | 188.3 |  8974.5 | 139.3 | 1.35x | String |
| json       |  3600.5 | 347.2 |  4417.1 | 283.0 | 1.23x | String |
| double     |  3802.3 | 328.7 |  4144.7 | 301.6 | 1.09x | Float |
| float      |  7018.2 | 178.1 |  7533.7 | 165.9 | 1.07x | Float |
| medium_int | 10692.5 | 116.9 | 11087.6 | 112.7 | 1.04x | Integer |
| bool       | 10756.2 | 116.2 | 11236.4 | 111.2 | 1.04x | Integer |
| tiny_int   | 10604.4 | 117.9 | 10935.3 | 114.3 | 1.03x | Integer |
| small_int  | 10311.9 | 121.2 | 10380.9 | 120.4 | 1.01x | Integer |
| int        | 10463.4 | 119.5 | 10615.6 | 117.8 | 1.01x | Integer |
| year       | 10172.6 | 122.9 | 10232.9 | 122.2 | 1.01x | Integer |
| big_int    |  9906.1 | 126.2 |  9668.1 | 129.3 | 0.98x | Integer |
| | | | | | | |
| `id` (1 col, row overhead) | 11826.1 | 845.6 | 12826.2 | 779.7 | 1.08x | |
| **geometric mean** | | | | | **1.48x** | |

## Reading the per-type numbers

**Temporals (2.5x - 3.75x) are the standout.** The C extension constructs each
`Time`/`Date` by calling back into Ruby per value; our caster parses the
text-protocol bytes straight into the object. On `time`/`datetime` that drops
per-value cost from ~670–740 ns down to ~195–220 ns across every run.

**Decimal (1.78x - 2.00x)** is the same story — `BigDecimal` construction dominates
on the C side.

**Strings and byte types (1.1x - 1.8x).** Building a `RubyString` from the wire
bytes beats CRuby's C-to-Ruby string allocation. `blob` and `json` trail the short
string types because their payloads are large enough that transfer starts to
outweigh the decode.

**Integers and floats are near parity (~0.9x - 1.1x).** Both sides sit near the
floor of "parse a short ASCII number", so what is left is per-row overhead — which
is nearly identical between the engines (`id (1 col)`: `1.01x - 1.08x`). With YJIT
enabled, CRuby trims the Ruby loop overhead around `each` (`10602 - 11582` vs
`8343 - 8898` ops/sec without YJIT), shifting short integers slightly in CRuby's
favor (`0.90x - 1.03x`).

What drives the integer group on JRuby is digit count, not object allocation. A
BIGINT column populated with values inside JRuby's Fixnum cache, outside it, and
12 digits wide — same column type, only the magnitude varying — measured on
`c1f97db`:

| BIGINT values | CRuby 3.4.8 ns/value | JRuby 10.0.6 ns/value |
|---|---:|---:|
| 0..199 (Fixnum-cached)  | 120.0 | 119.6 |
| 300..499 (uncached)     | 112.9 | 116.0 |
| 1e11 (12 digits)        | 123.7 | 129.5 |

Cached and uncached are indistinguishable — `400.equal?(400)` is already `true` on
JRuby 10 — while both engines slow on the wide values by a similar margin.

### On measurement noise

An earlier unpinned `c1f97db` run under the `powersave` governor gave a geometric
mean of `1.50x` against that run's `1.48x`, while pinning tightened per-scenario
scatter (the integer group went from `0.98x - 1.14x` to `0.98x - 1.04x`).

Treat anything under roughly **1.10x as parity**. On the 2026-11-17 non-YJIT run,
`medium_int` showed a single CRuby spike (`11003.6` vs `8343 - 8898` for all other
integers, giving `0.81x`), whereas `ints only` in `run.rb` measured `1.01x`.

Pinning matters on this CPU specifically: it is hybrid, and the two engines run
sequentially several minutes apart, so an unpinned comparison risks putting one leg
on 5157 MHz Zen5 cores and the other on 3289 MHz Zen5c cores — a systematic error
larger than most of what is being measured.

## Mixed query results

Both 2026-11-17 runs of `run.rb` (`BENCH_WARMUP=10`, `BENCH_TIME=5`, `BENCH_ROWS=1000`):

| scenario | CRuby (no YJIT) | JRuby (JDK 21) | ratio | CRuby (+YJIT) | JRuby (JDK 26) | ratio |
|---|---:|---:|---:|---:|---:|---:|
| temporals only    |    3269.7 |    7884.3 | **2.41x** |    3525.8 |    8866.7 | **2.51x** |
| query symbolize   |    1512.9 |    2820.0 | **1.86x** |    1736.0 |    3033.2 | **1.75x** |
| query as:array    |    1619.8 |    3195.1 | **1.97x** |    1913.0 |    3304.5 | **1.73x** |
| query+cast (hash) |    1436.5 |    2386.7 | 1.66x |    1708.8 |    2860.7 | 1.67x |
| query cast:false  |    2151.5 |    3131.2 | 1.46x |    2658.5 |    3620.8 | 1.36x |
| decimals/floats   |    7670.4 |   12462.5 | 1.62x |    9703.4 |   12977.6 | 1.34x |
| strings only      |    8800.3 |   13050.0 | 1.48x |   11957.9 |   13431.7 | 1.12x |
| wide, 1 row       |   20052.8 |   26683.4 | 1.33x |   25519.6 |   27878.7 | 1.09x |
| escape            | 4196512.4 | 4769296.4 | 1.14x | 4681973.7 | 5094947.3 | 1.09x |
| ints only         |   10603.6 |   10750.4 | 1.01x |   14378.0 |   13555.1 | 0.94x |
| **geometric mean** | | | **1.55x** | | | **1.40x** |

Even with YJIT active on CRuby, JRuby on OpenJDK 26 wins 9 of 10 mixed scenarios —
and by `1.67x - 1.75x` on full 26-column queries (`hash`, `as:array`, `symbolize`).

## ActiveRecord results

Rails' stock `mysql2` adapter driving the C extension on CRuby and this gem on
JRuby (`prepared_statements: false`, `BENCH_TIME=5`, `BENCH_ROWS=1000`).

### 2026-11-17: Rails 8.1.4.1 (CRuby 4.0.1 vs JRuby 10.1.3.0, `BENCH_WARMUP=40`)

| scenario | CRuby (no YJIT) | JRuby (JDK 21) | ratio | CRuby (+YJIT) | JRuby (JDK 26) | ratio |
|---|---:|---:|---:|---:|---:|---:|
| load + read attrs |  1455.3 |  2208.3 | 1.52x |  1960.7 |  2343.8 | **1.20x** |
| load + decimals   |  1371.9 |  2087.0 | 1.52x |  1849.8 |  2209.0 | **1.19x** |
| load 100 models   |  1623.4 |  2390.1 | 1.47x |  2106.8 |  2491.6 | **1.18x** |
| load + temporals  |  1397.8 |  2093.4 | 1.50x |  1916.6 |  2131.3 | **1.11x** |
| pluck 100 (3 col) |  3782.9 |  6131.0 | 1.62x |  5854.8 |  6449.4 | **1.10x** |
| count             |  3749.8 |  4164.1 | 1.11x |  4398.9 |  4229.3 | 0.96x |
| insert x25        |    71.2 |    72.2 | 1.01x |    83.8 |    74.3 | 0.89x |
| update x25        |    73.3 |    90.8 | 1.24x |    97.6 |    81.8 | 0.84x |
| find by pk        | 12282.2 | 15151.8 | 1.23x | 18307.0 | 15280.7 | 0.83x |
| pluck 100         |  8114.6 | 10894.5 | 1.34x | 13457.1 | 10741.5 | 0.80x |
| where.first       |  5784.3 |  7834.9 | 1.35x | 10170.0 |  7630.1 | 0.75x |
| **geometric mean** | | | **1.34x** | | | **0.97x** |

- **Bulk materialization stays faster on JRuby (`1.10x - 1.20x` vs `+YJIT`, `1.47x - 1.62x` without YJIT).**
  Loading 100 models or plucking multiple columns (`pluck 100 (3 col)`) shifts
  enough work into value/row decoding that `mysql2-jruby` keeps JRuby ahead even
  with YJIT enabled on CRuby.
- **YJIT flips single-row and dispatch-heavy queries.** Enabling YJIT boosts CRuby
  `where.first` from `5784 -> 10170 ops/sec` (`+76%`) and `find by pk` from
  `12282 -> 18307 ops/sec` (`+49%`), whereas JRuby's throughput on those short
  queries is dominated by AR query/relation Ruby dispatch rather than the driver.

### 2026-07-28 (`c1f97db`): Rails 8.0.5 (CRuby 3.4.8 vs JRuby 10.0.6.0, `BENCH_WARMUP=60`)

```sh
BENCH_CPUS=0-3,12-15 BENCH_WARMUP=60 BENCH_TIME=5 BENCH_ROWS=1000 \
  ruby benchmark/compare.rb active_record.rb
```

`ops/sec` are from the 60s-warmup run; the 15s column is kept to show where JRuby
had not converged:

| scenario | CRuby ops/sec | JRuby ops/sec | ratio | ratio @15s |
|---|---:|---:|---:|---:|
| pluck 100 (3 col) |  3617.3 |  6333.6 | 1.75x | 1.67x |
| load 100 models   |  1442.5 |  2464.9 | 1.71x | 1.62x |
| load + decimals   |  1222.2 |  2042.6 | 1.67x | 1.62x |
| load + read attrs |  1280.0 |  2120.1 | 1.66x | 1.53x |
| load + temporals  |  1241.5 |  2007.2 | 1.62x | 1.58x |
| pluck 100         |  7239.7 | 10500.5 | 1.45x | 1.27x |
| where.first       |  5213.2 |  7164.1 | 1.37x | 1.14x |
| update x25        |    74.4 |    88.5 | 1.19x | 1.07x |
| count             |  3259.1 |  3761.8 | 1.15x | 1.02x |
| find by pk        | 10381.6 | 11481.8 | 1.11x | 1.20x |
| insert x25        |    77.7 |    86.5 | 1.11x | 1.61x |
| **geometric mean** | | | **1.41x** | 1.37x |

**Per-type differences wash out inside ActiveRecord.** Through a model,
`load + temporals`, `load + decimals`, and `load + read attrs` are nearly
indistinguishable across every run: AR's per-attribute machinery costs enough to
dominate whatever the driver does with the bytes.

### Caveats specific to the AR suite

**Warmup matters far more here than at the driver level, and short warmups distort results.**
AR is a lot more Ruby to JIT. On Rails 8.1.4.1 / JDK 26, a 0.5s warmup smoke run
scored only `~12 - 1745 ops/sec` on JRuby versus `2131 - 15281 ops/sec` at
`BENCH_WARMUP=40`. On the earlier 8.0.5 run, comparing `BENCH_WARMUP=15` against
`BENCH_WARMUP=60`:

| scenario | CRuby @15s | CRuby @60s | CRuby delta | JRuby @15s | JRuby @60s | JRuby delta |
|---|---:|---:|---:|---:|---:|---:|
| find by pk        | 10045.1 | 10381.6 | +3.4% | 12025.3 | 11481.8 |  -4.5% |
| where.first       |  5171.6 |  5213.2 | +0.8% |  5894.4 |  7164.1 | +21.5% |
| load 100 models   |  1456.5 |  1442.5 | -1.0% |  2363.3 |  2464.9 |  +4.3% |
| load + read attrs |  1272.3 |  1280.0 | +0.6% |  1948.4 |  2120.1 |  +8.8% |
| load + temporals  |  1233.9 |  1241.5 | +0.6% |  1953.2 |  2007.2 |  +2.8% |
| load + decimals   |  1210.7 |  1222.2 | +0.9% |  1961.0 |  2042.6 |  +4.2% |
| pluck 100         |  7371.4 |  7239.7 | -1.8% |  9331.4 | 10500.5 | +12.5% |
| pluck 100 (3 col) |  3598.9 |  3617.3 | +0.5% |  6002.1 |  6333.6 |  +5.5% |
| count             |  3362.2 |  3259.1 | -3.1% |  3432.1 |  3761.8 |  +9.6% |
| insert x25        |    76.3 |    77.7 | +1.8% |   123.1 |    86.5 | -29.7% |
| update x25        |    77.5 |    74.4 | -4.0% |    82.7 |    88.5 |  +7.0% |

CRuby lands within +-4% either way — it is warm almost immediately — while JRuby
gained up to +21.5% on the read scenarios between 15s and 60s. **Use
`BENCH_WARMUP=40`–`60` for numbers worth quoting.**

**Writes remain partly disk-bound even batched.** `insert x25` at `~71-84 ops/sec`
is 12–14 ms per iteration for 25 rows: roughly 0.5 ms/row plus one ~5 ms commit
fsync, so about 40% of the figure is the disk. Unbatched, single-row autocommit
writes measured 4–7 ms/op on both engines, entirely fsync. `insert x25` and
`update x25` stay the least reliable rows in the table.
