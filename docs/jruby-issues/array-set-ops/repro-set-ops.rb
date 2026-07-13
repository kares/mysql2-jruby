# Minimized reproducer: which Array set-ops call #hash on their elements.
#
# MRI uses a linear eql?/== scan for small arrays (<=16 elements) in several of these and
# never touches #hash; JRuby builds a hash set and calls #hash. An element whose #hash
# raises therefore behaves differently across engines.

class Poison
  def hash
    raise "#hash called"
  end

  def eql?(o)
    equal?(o)
  end

  def ==(o)
    equal?(o)
  end
end

puts "ruby: #{RUBY_ENGINE} #{RUBY_ENGINE_VERSION} (#{RUBY_VERSION})"

def check(label)
  yield
  puts "%-24s => OK   (no #hash)" % label
rescue => e
  puts "%-24s => RAISED (%s)" % [label, e.message]
end

a = Poison.new
b = Poison.new

check("[a,b] - [b]")        { [a, b] - [b] }
check("[a,b] & [b]")        { [a, b] & [b] }
check("[a,b] | [b]")        { [a, b] | [b] }
check("[a,b].uniq")         { [a, b].uniq }
check("[a,b].difference")   { [a, b].difference([b]) }
check("[a,b].intersection") { [a, b].intersection([b]) }
check("[a,b].union")        { [a, b].union([b]) }
check("[a,b].intersect?")   { [a, b].intersect?([b]) if [].respond_to?(:intersect?) }
check("[a,b] - []")         { [a, b] - [] }
check("[a,b] & []")         { [a, b] & [] }
