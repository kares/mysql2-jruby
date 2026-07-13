# JRuby core bug reports

Adapter-independent JRuby (vs MRI) incompatibilities found while running the ActiveRecord
suite against this gem. Each is a candidate to report upstream (JRuby / Rails); the
corresponding tests are temporarily excluded in `test/rails/excludes/mysql2/`.

## `array-set-ops/` — `Array#-`, `#&`, `#|` call `#hash`
JRuby's `Array#-`/`#&`/`#|` build a hash set and call `#hash` on elements, where MRI uses a
linear `==`/`eql?` scan for small arrays and never calls `#hash`. Breaks Rails' cached
`find_by` (`WhereClause#-` → `QueryAttribute#hash` serializes a `StatementCache::Substitute`).
Confirmed JRuby 9.2–10.0. `repro-minus.rb` (minimal), `repro-set-ops.rb` (full matrix).

## `marshal-dump/` — `Marshal.dump` writes off-by-one symbol back-references
JRuby's `Marshal.dump` emits a symbol into the stream without advancing its internal
symbol→index table, so later symbol back-references are written one index too small; a
class-name reference then resolves to an `@ivar` symbol and `Marshal.load` raises
`undefined class/module @handlers`. Proven a dump-side bug by cross-loading (MRI cannot read
JRuby's bytes). `repro.rb` (ActiveRecord, ~15 lines); `mdiff.rb` is a Marshal-stream decoder
that pinpoints the anomaly and can verify a fix (`DUMP_IN=<dump> ruby mdiff.rb`).
