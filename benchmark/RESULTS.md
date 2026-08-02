# Benchmark results

CRuby + the `mysql2` C extension vs JRuby + this gem. Produced by
`benchmark/compare.rb` (see [README.md](README.md) for the methodology and how to
reproduce). Two levels:

- [driver](#per-type-results) — `types.rb`, decoding each MySQL column type
- [ActiveRecord](#activerecord-results) — `active_record.rb`, the same comparison
  one layer up

```sh
BENCH_CPUS=0-3,12-15 BENCH_WARMUP=10 BENCH_TIME=5 BENCH_ROWS=1000 rake bench:compare
```

## Overall: how much faster is JRuby?

**About 1.5x at the driver level and 1.4x through ActiveRecord**, as a geometric mean
over all scenarios. JRuby + this gem is faster in both suites — the two levels differ
in shape, not in direction.

| | geometric mean | range | scenarios below 1.00x |
|---|---:|---|---:|
| driver (`types.rb`, 26 scenarios)      | **1.48x** | 0.98x - 3.75x | 1 |
| ActiveRecord (`active_record.rb`, 11)  | **1.41x** | 1.11x - 1.75x | 0 |

The *average* advantage is slightly larger without Rails, but the *floor* is higher
with it. ActiveRecord's own overhead is the same work on both engines, so it dilutes
both ends: the 3.75x `datetime` win compresses to 1.62x, while the integer scenarios
that sat at parity are lifted clear of it. Nothing gets worse once Rails is in the
picture — the spread just narrows.

The single sub-parity result anywhere is `big_int` at 0.98x, at the driver level, and
it is inside this machine's noise floor (see [measurement noise](#on-measurement-noise)).
It is not a Rails-level result — every ActiveRecord scenario is a JRuby win.

Read any individual number with the caveats attached to its section: at the driver
level the useful figure is `ns/value`, and under ActiveRecord the numbers are only
meaningful after a long warmup.

## Environment

| | |
|---|---|
| date | 2026-07-28 |
| gem revision | `c1f97db` |
| CRuby | 3.4.8, `mysql2` 0.5.7 (linked against libmariadb 3.4.9) |
| JRuby | 10.0.6.0, `mysql2-jruby` 0.5.7.0, Connector/J 9.4.0, OpenJDK 21.0.11 |
| MySQL | 8.0.46 in a container on `127.0.0.1:13306` |
| CPU | AMD Ryzen AI 9 HX PRO 370 (4x Zen5 @ 5157 MHz + 8x Zen5c @ 3289 MHz) |
| OS | Fedora 44, kernel 7.1.5 |
| governor | `performance` + EPP `performance`, boost on |
| pinning | both engines `taskset -c 0-3,12-15` (the Zen5 cores) |
| scenario | 100 rows x 8 aliases of one column per query, 10s warmup, 5s measured |

## Per-type results

Ratio is JRuby / CRuby; `> 1.00x` means this gem is faster. `ns/value` is the
per-column-value decode cost, the more meaningful figure — `ops/sec` counts whole
queries and so carries the fixed per-query overhead.

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

Every type decodes to the same Ruby class on both engines, so each row compares
like with like. `compare.rb` flags any type where that stops being true.

## Reading the numbers

**Temporals (2.9x - 3.75x) are the standout.** The C extension constructs each
`Time`/`Date` by calling back into Ruby per value; our caster parses the
text-protocol bytes straight into the object. On `datetime` that is 743 -> 198
ns/value, the largest gap in the suite.

**Decimal (2.00x)** is the same story — `BigDecimal` construction dominates on the
C side.

**Strings and byte types (1.35x - 1.81x).** Building a `RubyString` from the wire
bytes is close to free for us. `blob` (1.35x) and `json` (1.23x) trail the rest
because their payloads are large enough that transfer starts to outweigh the decode.

**Integers and floats are parity (0.98x - 1.09x).** Both sides sit near the floor of
"parse a short ASCII number", so what is left is per-row overhead — which is nearly
identical between the engines (`id (1 col)`: 1.08x). `big_int` at 0.98x is the only
scenario where CRuby edges ahead, and it is within noise.

What drives the integer group is digit count, not JRuby's object allocation. A BIGINT
column populated with values inside JRuby's Fixnum cache, outside it, and 12 digits
wide — same column type, only the magnitude varying — gives:

| BIGINT values | CRuby ns/value | JRuby ns/value |
|---|---:|---:|
| 0..199 (Fixnum-cached)  | 120.0 | 119.6 |
| 300..499 (uncached)     | 112.9 | 116.0 |
| 1e11 (12 digits)        | 123.7 | 129.5 |

Cached and uncached are indistinguishable — `400.equal?(400)` is already `true` on
JRuby 10 — while both engines slow on the wide values by a similar margin. All that
remains is JRuby running ~3-5% behind on the longest digit strings, which is exactly
the size of the `big_int` result and inside the noise floor.

## On measurement noise

An earlier unpinned run under the `powersave` governor gave a geometric mean of
1.50x against this run's 1.48x, so the controls did not change any conclusion. What
they tightened is the per-scenario scatter: the integer group went from 0.98x - 1.14x
to 0.98x - 1.04x (`bool` alone moved 1.14x -> 1.04x), which is the signature of noise
being removed rather than a real effect disappearing.

Treat anything under roughly **1.10x as parity**. Differences of 1.3x and up held
steady across both runs.

Pinning matters on this CPU specifically: it is hybrid, and the two engines run
sequentially several minutes apart, so an unpinned comparison risks putting one leg
on 5157 MHz Zen5 cores and the other on 3289 MHz Zen5c cores — a systematic error
larger than most of what is being measured.

## ActiveRecord results

Same machine and controls as above, Rails' stock mysql2 adapter driving the C
extension on CRuby and this gem on JRuby. ActiveRecord 8.0.5 on both sides —
comparing different AR versions would measure AR rather than the drivers.

```sh
BENCH_CPUS=0-3,12-15 BENCH_WARMUP=60 BENCH_TIME=5 BENCH_ROWS=1000 \
  ruby benchmark/compare.rb active_record.rb
```

`prepared_statements: false` (the adapter default), 5s measured. ops/sec are from
the 60s-warmup run; the 15s column is kept to show where JRuby had not converged.

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

The driver-level advantage survives ActiveRecord — 1.41x here against 1.48x at the
driver level — but it changes shape:

**Per-type differences wash out.** At the driver level temporals were 2.9x - 3.75x
and decimals 2.00x, against ~1.5x for strings and ~1.0x for integers. Through a
model, `load + temporals` (1.62x), `load + decimals` (1.67x) and `load + read attrs`
(1.66x) are indistinguishable: AR's per-attribute machinery costs enough to dominate
whatever the driver does with the bytes. The 3.75x `datetime` win compresses to 1.62x.

**Not all of the win is the driver.** `count` returns one scalar — nothing to decode,
no models to instantiate — yet still runs 1.15x faster. That has to be JRuby
executing ActiveRecord's own Ruby faster once JIT-ed, and it puts a floor under every
other scenario in the table.

**`load 100 models` (1.71x) beats `find by pk` (1.11x).** The more rows and columns
materialized, the more the driver matters relative to fixed per-query overhead.

### Caveats specific to the AR suite

**Warmup matters far more here than at the driver level, and 15s is not enough.**
AR is a lot more Ruby to JIT. At a 1s warmup JRuby reported `load + read attrs`
(451 ops/sec) as *faster* than `load 100 models` (276) — impossible, since it is
strictly more work: the scenarios were ranking themselves by how far the JIT had got
when each one started. **Use `BENCH_WARMUP=60` for numbers worth quoting**; 15s is
enough only for the `load *` scenarios.

Both runs in full, same machine and controls, only `BENCH_WARMUP` differing:

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

CRuby lands within +-4% either way — it is warm almost immediately, so that column is
the noise floor for this machine. JRuby gains up to +21.5% on the read scenarios, well
outside that floor, which is what "still warming at 15s" looks like. The two write
rows move in opposite directions by far more than either engine's compute changed;
that is the fsync variance described below, not JIT.

**Writes remain partly disk-bound even batched.** `insert x25` at ~78 ops/sec is 13 ms
per iteration for 25 rows: roughly 0.5 ms/row plus one ~5 ms commit fsync, so about
40% of the figure is the disk. Unbatched, it is far worse — single-row autocommit
writes measured 4 - 7 ms/op on both engines, entirely fsync, which is why the first
draft of this suite appeared to show CRuby inserting 6x faster. Dropping below AR to
raw `Mysql2::Client` showed the opposite (JRuby 231/s vs CRuby 141/s). At 15s warmup
`insert x25` (1.61x) and `update x25` (1.07x) disagreed wildly; at 60s they converge
to 1.11x and 1.19x, confirming that spread was fsync variance rather than a real
difference between the two paths. These two rows stay the least reliable in the table.
