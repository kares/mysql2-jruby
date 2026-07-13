# Correct Marshal-4.8 decoder that tracks the symbol table and the object-link table with
# MRI registration semantics, so we can (1) validate it fully consumes a known-good MRI
# stream, then (2) run it on JRuby's stream to find the first structural anomaly / desync
class Dec
  attr_reader :symbols, :objects, :log, :anomaly
  def initialize(bytes); @b = bytes.b; @i = 0; @symbols = []; @objects = []; @log = []; end
  def pos; @i; end
  def size; @b.bytesize; end
  def byte; x = @b.getbyte(@i); @i += 1; x; end
  def sbyte; x = byte; x >= 128 ? x - 256 : x; end
  def long
    c = sbyte
    return 0 if c == 0
    if c > 0
      return c - 5 if c > 4
      r = 0; c.times { |k| r |= byte << (8*k) }; r
    else
      return c + 5 if c < -4
      n = -c; r = -1; n.times { |k| r &= ~(0xff << (8*k)); r |= byte << (8*k) }; r
    end
  end
  def bytes_n; n = long; s = @b.byteslice(@i, n); @i += n; s; end

  # register an object slot (MRI r_entry). returns index.
  def reg(kind); @objects << kind; @objects.size - 1; end

  # read a symbol reference; expect = :class (class/module name) or :ivar (ivar name) or nil.
  def rsym(expect = nil)
    off = @i; t = byte; c = t.chr
    if c == ':'
      s = bytes_n; @symbols << s; flag(off, s, expect, nil); s
    elsif c == ';'
      idx = long; s = @symbols[idx] || "?#{idx}"; flag(off, s, expect, idx); s
    else
      @anomaly ||= [off, "expected symbol, got #{c.inspect}", @objects.size, @symbols.size]
      "?"
    end
  end
  def flag(off, s, expect, idx)
    return unless idx   # only SYMLINK back-references can be mis-indexed; new defs are literal
    if expect == :class && s.to_s.start_with?("@")
      @anomaly ||= [off, "CLASS-name position resolved to IVAR symbol #{s.inspect} (symlink ##{idx})", @objects.size, @symbols.size]
    elsif ENV["IVARCHK"] && expect == :ivar && !s.to_s.start_with?("@")
      # NOTE: noisy — Range/Rational/etc. legitimately use non-@ field names on re-reference.
      @anomaly ||= [off, "IVAR-name position resolved to non-ivar symbol #{s.inspect} (symlink ##{idx})", @objects.size, @symbols.size]
    end
  end

  def val(expect = nil)
    off = @i; t = byte
    return "EOF" if t.nil?
    c = t.chr
    d = case c
    when '0' then "nil"
    when 'T' then "true"
    when 'F' then "false"
    when 'i' then "int#{long}"
    when ':' then @i -= 1; "sym(#{rsym(expect)})"
    when ';' then @i -= 1; "symlink(#{rsym(expect)})"
    when '@'
      idx = long
      tgt = @objects[idx]
      if tgt.nil?
        @anomaly ||= [off, "object LINK ##{idx} out of range (have #{@objects.size})", @objects.size, @symbols.size]
        tgt = "OOR"
      end
      "link##{idx}->#{tgt}"
    when '"' then oi = reg("str"); "str##{oi}(#{bytes_n.inspect})"
    when 'f' then oi = reg("float"); "float##{oi}(#{bytes_n})"
    when 'I' then v = val(expect); n = long; n.times { rsym(nil); val }; "ivar[#{v}]"  # I-ivars may be E/encoding
    when 'e' then rsym(:class); "ext(#{val(expect)})"
    when 'o' then oi = reg("obj"); cn = rsym(:class); n = long; n.times { rsym(:ivar); val }; "obj##{oi}:#{cn}"
    when 'U' then oi = reg("U"); cn = rsym(:class); "U##{oi}:#{cn}[#{val}]"
    when 'u' then oi = reg("u"); cn = rsym(:class); bytes_n; "u##{oi}:#{cn}"
    when 'd' then oi = reg("data"); cn = rsym(:class); "data##{oi}:#{cn}[#{val}]"
    when '[' then oi = reg("ary"); n = long; els = Array.new(n) { val }; "ary##{oi}(#{n})"
    when '{' then oi = reg("hash"); n = long; n.times { val; val }; "hash##{oi}(#{n})"
    when '}' then oi = reg("hashdef"); n = long; n.times { val; val }; val; "hashdef##{oi}(#{n})"
    when 'C' then cn = rsym(:class); "uclass:#{cn}[#{val}]"     # NO own slot; inner registers
    when 'c' then oi = reg("class"); "class##{oi}(#{bytes_n})"
    when 'm' then oi = reg("module"); "module##{oi}(#{bytes_n})"
    when 'S' then oi = reg("struct"); cn = rsym(:class); n = long; n.times { rsym(nil); val }; "struct##{oi}:#{cn}"
    when 'l' then oi = reg("bignum"); byte; n = long; @i += n*2; "bignum##{oi}"
    when '/' then oi = reg("regexp"); s = bytes_n; byte; "regexp##{oi}"
    else
      @anomaly ||= [off, "unknown type byte #{c.inspect} (0x%02X)" % t, @objects.size, @symbols.size]
      "??#{c.inspect}"
    end
    @log << [off, d]
    d
  end

  def run
    byte; byte  # 0x04 0x08
    val
  end
end

bytes = File.binread(ENV["DUMP_IN"])
d = Dec.new(bytes)
begin
  d.run
rescue => e
  puts "parser raised: #{e.class}: #{e.message} at byte #{d.pos}"
end
consumed = d.pos
puts "consumed #{consumed}/#{d.size}  (#{consumed == d.size ? 'ALL-OK' : 'SHORT'})"
puts "symbols=#{d.symbols.size} objects=#{d.objects.size}"
if d.anomaly
  off, msg, oc, sc = d.anomaly
  puts "\n*** ANOMALY at byte #{off}: #{msg}\n    (object-table=#{oc}, symbol-table=#{sc} at that point)"
  puts "\nlast tokens before anomaly:"
  d.log.select { |o, _| o <= off }.last(14).each { |o, x| puts "  @#{o}  #{x}" }
else
  puts "no anomaly flagged"
end

if ENV["RANGE"]
  a, b = ENV["RANGE"].split("-").map(&:to_i)
  puts "\n-- symbols #{a}..#{b} --"
  (a..b).each { |i| puts "  #{i}: #{d.symbols[i].inspect}" }
end

if ENV["SYMS"]
  puts "\n-- symbol table --"
  d.symbols.each_with_index { |s, i| puts "  #{i}: #{s.inspect}" if s.to_s =~ /ArrayHandler|@handlers|Set|Array\z|ActiveRecord::PredicateBuilder\z/ }
  ah = d.symbols.index("ActiveRecord::PredicateBuilder::ArrayHandler")
  hd = d.symbols.index("@handlers")
  puts "\nArrayHandler canonical index = #{ah}; @handlers index = #{hd}; JRuby used 94 -> drift = #{94 - ah} (should have used #{ah})"
end

if ENV["DUPS"]
  seen = Hash.new { |h,k| h[k] = [] }
  d.symbols.each_with_index { |s, i| seen[s] << i }
  dups = seen.select { |s, idxs| idxs.size > 1 }
  puts "\nDUPLICATE symbols in stream: #{dups.empty? ? 'NONE' : dups.size}"
  dups.each { |s, idxs| puts "  #{s.inspect} at indices #{idxs.inspect}" }
end
