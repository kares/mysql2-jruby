# `Array#-`, `#&`, `#|` call `#hash` on elements where MRI uses a linear `==`/`eql?` scan for small arrays

## Summary

For small arrays, MRI's `Array#-`, `Array#&`, and `Array#|` compare elements with `eql?`/`==`
and never call `#hash`. JRuby always builds a hash set and calls `#hash` on the elements.

As a result, an element whose `#hash` raises (or is expensive/side-effecting) behaves
differently across engines: the operation succeeds on MRI but raises on JRuby. This is a
Ruby-compatibility divergence, not a crash in JRuby itself.

There is also an internal inconsistency in JRuby: `Array#-` raises but its documented alias
`Array#difference` does not; likewise `Array#|` raises but `Array#union` does not. The
operator and its named equivalent should behave identically.

## Reproducer

```ruby
class Poison
  def hash
    raise "#hash called"
  end
  def eql?(o) = equal?(o)
  def ==(o)   = equal?(o)
end

a, b = Poison.new, Poison.new

p([a, b] - [b])   # MRI => [a]      JRuby => raises "#hash called"
p([a, b] & [b])   # MRI => [b]      JRuby => raises "#hash called"
p([a, b] | [b])   # MRI => [a, b]   JRuby => raises "#hash called"
```

## Behavior matrix (non-empty operands, 2-element arrays)

| Operation             | MRI 3.4 | JRuby 9.4 | JRuby 10.0 | Divergent from MRI |
|-----------------------|:-------:|:---------:|:----------:|:------------------:|
| `a - b`               | ok      | **raise** | **raise**  | yes                |
| `a & b`               | ok      | **raise** | **raise**  | yes                |
| `a \| b`              | ok      | **raise** | **raise**  | yes                |
| `a.intersection(b)`   | ok      | **raise** | **raise**  | yes                |
| `a.difference(b)`     | ok      | ok        | ok         | no (inconsistent with `-`) |
| `a.union(b)`          | ok      | ok        | ok         | no (inconsistent with `\|`) |
| `a.intersect?(b)`     | ok      | ok        | ok         | no                 |
| `a.uniq`              | raise   | raise     | raise      | no (MRI hashes too) |
| `a - []` (empty rhs)  | ok      | ok        | ok         | no (short-circuit) |

Confirmed on JRuby 9.2.19, 9.3.15, 9.4.14, and 10.0.6 — all affected identically, so this is
long-standing behavior, not a recent regression. MRI reference: 3.4.8.

`uniq` is listed only to note it is NOT a divergence (MRI hashes for `uniq` as well), and the
empty-rhs case is listed to show MRI-and-JRuby both short-circuit — this is why the divergence
only appears when both operands are non-empty.

## Expected behavior

Match MRI: for small arrays these operations should not call `#hash`. MRI's implementation
(`rb_ary_diff`, `rb_ary_and`, `rb_ary_or`) uses a linear `rb_ary_includes`-style scan
(`==`/`eql?`) when the arrays are below a small threshold (`SMALL_ARRAY_LEN`, 16 in current
MRI) and only builds a hash set for larger inputs. JRuby builds the hash set unconditionally.

At minimum, `Array#-`/`#difference` and `Array#|`/`#union` should be made consistent with each
other.

## Real-world impact

Hit in ActiveRecord on JRuby. `ActiveRecord::Relation::WhereClause#-` does
`predicates - other.predicates`. A predicate's `#hash` (`QueryAttribute#hash`) eagerly
serializes its bound value. On the cached `find_by` path that value is a
`StatementCache::Substitute` placeholder, and serializing it raises. Under MRI the small-array
`-` never calls `#hash`, so it is harmless; under JRuby it raises, breaking `find_by` for
Float / normalized / serialized / out-of-range columns. Several ActiveRecord tests fail on
JRuby purely because of this.

## Environment

- JRuby 9.2.19.0, 9.3.15.0, 9.4.14.0, 10.0.6.0
- Compared against CRuby/MRI 3.4.8
