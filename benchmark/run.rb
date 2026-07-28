# Mixed-scenario performance suite comparing MRI mysql2 (C ext) vs JRuby + this gem.
# See types.rb for the per-column-type breakdown.
#
#   ruby  benchmark/run.rb     # MRI, uses the installed mysql2 gem
#   jruby benchmark/run.rb     # JRuby, uses this gem + Connector/J
#
# Both engines expose the same Mysql2::Client API, so the same scenarios run on
# each; run once per engine and compare the ops/sec. Env knobs:
#   BENCH_ROWS=1000   rows seeded in the table
#   BENCH_WARMUP=3    warmup seconds per scenario (matters for JRuby JIT)
#   BENCH_TIME=5      measured seconds per scenario
#   BENCH_SETUP=0     skip re-seeding (table already populated)
require_relative 'support'

client = Bench.client
Bench.setup!(client) unless ENV['BENCH_SETUP'] == '0'

t = Bench::TABLE
q100 = "SELECT * FROM #{t} LIMIT 100"

runner = Bench::Runner.new(
  Bench.engine_label,
  "rows=#{Bench::ROWS}  warmup=#{Bench::WARMUP}s  time=#{Bench::TIME}s"
)

runner.measure("query+cast (hash)")   { client.query(q100).each { |r| r } }
runner.measure("query cast:false")    { client.query(q100, cast: false).each { |r| r } }
runner.measure("query as:array")      { client.query(q100, as: :array).each { |r| r } }
runner.measure("query symbolize")     { client.query(q100, symbolize_keys: true).each { |r| r } }
runner.measure("ints only") do
  client.query("SELECT tiny_int_test, int_test, big_int_test FROM #{t} LIMIT 100").each { |r| r }
end
runner.measure("strings only") do
  client.query("SELECT char_test, varchar_test, text_test FROM #{t} LIMIT 100").each { |r| r }
end
runner.measure("temporals only") do
  client.query("SELECT date_test, date_time_test, timestamp_test, time_test FROM #{t} LIMIT 100").each { |r| r }
end
runner.measure("decimals/floats") do
  client.query("SELECT float_test, decimal_test FROM #{t} LIMIT 100").each { |r| r }
end
runner.measure("wide, 1 row")         { client.query("SELECT * FROM #{t} LIMIT 1").each { |r| r } }
runner.measure("escape")              { client.escape("some 'value' with \\ chars and \"quotes\"") }

runner.finish!
client.close
