# `Marshal.dump` writes symbol back-references with a wrong (off-by-one) index, corrupting the stream (`undefined class/module @&lt;ivar&gt;` on reload)

## Summary

For certain cyclic object graphs, JRuby's `Marshal.dump` emits a **malformed stream**: the
symbol/object link table is written with an off index, so a class/module reference in the
stream ends up pointing at an *instance-variable-name* symbol. Reloading then fails with

```
ArgumentError: undefined class/module @<some ivar name>
```

The tell-tale sign that this is a link-table indexing problem (not a missing class) is that
the reported name is always an **`@`-prefixed ivar symbol** that legitimately occurs earlier
in the same stream (`@handlers`, `@type`, `@table`, `@attribute`, …), and *which* one is
reported shifts with the graph.

This is a **dump** bug, proven by cross-loading (below): MRI cannot read JRuby's output, while
JRuby reads MRI's output of the same object fine.

## Reproducer

Smallest reliable trigger I have is an ActiveRecord graph — a new record with an in-memory
association built, which creates a cycle (`association -> owner -> record -> association_cache`).

```ruby
require "active_record"
#require "activerecord-jdbcsqlite3-adapter.rb" if defined? JRUBY_VERSION
ActiveRecord::Base.establish_connection(adapter: "sqlite3", database: ":memory:")
c = ActiveRecord::Base.connection
c.create_table(:posts,    force: true) { |t| t.string :title }
c.create_table(:comments, force: true) { |t| t.integer :post_id; t.string :body }
class Post    < ActiveRecord::Base; has_many   :comments; end
class Comment < ActiveRecord::Base; belongs_to :post;     end

post = Post.new
Marshal.load(Marshal.dump(post))          # OK on both

post.comments.build                       # populate association cache -> cyclic graph
Marshal.load(Marshal.dump(post))          # MRI: OK   JRuby: ArgumentError: undefined class/module @handlers
```

- MRI (3.4): both lines succeed.
- JRuby 10.0.6.0 (3.4.5): the second line raises `undefined class/module @handlers`.

(On JRuby use a JDBC adapter for the connection, e.g. `activerecord-jdbcsqlite3` or
`activerecord-jdbcmysql`; the adapter is irrelevant to the bug — it reproduces with MySQL over
Connector/J as well. AR 8.0 requires Ruby >= 3.2, so this graph can only be exercised on
JRuby 10.x, not 9.4.)

## Proof it is a dump (not load) bug

Same logical object, dumped on one engine and loaded on the other:

| dump engine | load engine | result |
|---|---|---|
| **JRuby** | MRI       | **fails: `undefined class/module @handlers`** (MRI, the reference loader, cannot read JRuby's bytes) |
| MRI        | **JRuby** | parses the structure fine (only later trips on an adapter-specific type constant, i.e. unrelated) |
| JRuby      | JRuby     | fails (`undefined class/module @handlers`) |
| MRI        | MRI       | OK |

So JRuby's `Marshal.dump` writes a stream that violates the format's symbol-table indexing;
JRuby's `Marshal.load` is not implicated.

## Root cause (exact)

I decoded JRuby's byte stream with a Marshal-4.8 reader that tracks the symbol table and the
object-link table using MRI's registration semantics. Validation: the reader consumes a
known-good MRI dump of the equivalent object completely, flagging nothing. On JRuby's dump it
flags exactly one anomaly:

```
byte 2277: CLASS-name position resolved to IVAR symbol "@handlers" (symlink #94)
           (symbol-table size = 96 at that point)
```

The faulting bytes are the class name of the **second `ArrayHandler` object** inside
`PredicateBuilder`'s `@handlers` list (`@handlers = [[Array, ArrayHandler], [Set, ArrayHandler], …]`).
Its class name is written as a **symbol back-reference (symlink) to index 94** — but in the
stream:

```
symbol #93 = "ActiveRecord::PredicateBuilder"
symbol #94 = "@handlers"                                  <- JRuby emitted this symlink
symbol #95 = "ActiveRecord::PredicateBuilder::ArrayHandler"  <- ...but THIS is the intended class
```

So the symlink index is **off by one (−1)**. There are **no duplicate symbols** in the stream,
so the cause is: JRuby emitted the `@handlers` symbol definition into the stream but **did not
advance its internal symbol→index table for it**, so the next symbol (`ArrayHandler`) is
mapped to 94 internally while physically occupying 95 in the stream. Every symbol
back-reference after that point is shifted; the first one that lands in a class-name position
resolves to the `@handlers` ivar symbol, and `Marshal.load` raises
`undefined class/module @handlers`.

In short: **a dump-time symbol-table bookkeeping error — one emitted symbol is not counted, so
subsequent symbol back-references are written with indices one too small.**

The decoder used for this analysis is attached (`mdiff.rb`); point it at a `Marshal.dump`
output via `DUMP_IN=…`. It's a compact, reusable way to verify any fix (a corrected dump makes
the anomaly disappear).

## On a pure-Ruby reproducer

I could **not** reduce this to an AR-free case despite ~30 targeted experiments. Individually,
none of these reproduce it on JRuby: plain/mutual cycles, shared references, `Set`s of cyclic
objects, objects with custom `marshal_dump` in cycles (incl. many linking back to a shared
root), `TYPE_UCLASS` wrappers (`Hash`/`Array` subclasses, `compare_by_identity` hashes),
object-links into those, `Range` values embedded mid-ivars, repeated `Class`-object +
repeated-class-name pairs, scaling the symbol table to 90–200 entries, and the exact
`PredicateBuilder`-shaped `@handlers = [[Class, handler], …]` graph with handler→owner cycles.
The symbol miscount only manifests in the full accumulated AR graph, which suggests the skipped
`@handlers` registration depends on a specific interaction of preceding constructs rather than
any single one. The AR reproducer above is tiny and 100% reliable, and the root-cause analysis
localizes the bug precisely to dump-time symbol registration.

## Impact

Breaks `Marshal.dump` of ActiveRecord models that have in-memory-built or loaded associations
(a common pattern: caching, `Marshal`-based session/cache stores, cross-process handoff).
Several ActiveRecord 8.0 test-suite cases fail on JRuby solely because of this.

## Environment

- JRuby 10.0.6.0 (Ruby 3.4.5), Java 21
- Compared against CRuby/MRI 3.4.8
- ActiveRecord 8.0.5 (marshalling format_version 6.1, the default)
