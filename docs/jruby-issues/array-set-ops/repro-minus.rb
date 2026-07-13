# Minimized reproducer: Array#- element #hash behavior.
#
# Rails' ActiveRecord::Relation::WhereClause#- does `predicates - other.predicates`.
# The predicate elements' #hash eagerly serializes the bound value. When that value is a
# StatementCache::Substitute placeholder (cached find_by), serialize raises -- but only if
# Array#- actually calls #hash. MRI's Array#- uses linear ==/eql? for small arrays (<=16
# elements) and never touches #hash; JRuby builds a hash set and calls #hash.

class Poison
  def hash
    raise "#hash called (Array#- hashed its elements)"
  end
  # MRI's small-array path compares with eql?/== only:
  def eql?(o)
    equal?(o)
  end

  def ==(o)
    equal?(o)
  end
end

a = Poison.new
b = Poison.new

puts "ruby: #{RUBY_ENGINE} #{RUBY_ENGINE_VERSION} (#{RUBY_VERSION})"

# Small arrays (2 elements, well under MRI's 16 threshold):
begin
  result = [a, b] - [b]
  puts "[a, b] - [b]  => OK, size #{result.size} (no #hash call)"
rescue => e
  puts "[a, b] - [b]  => RAISED: #{e.message}"
end

# Single element minus empty -- MRI short-circuits, still worth checking:
begin
  [a] - []
  puts "[a] - []      => OK (no #hash call)"
rescue => e
  puts "[a] - []      => RAISED: #{e.message}"
end
