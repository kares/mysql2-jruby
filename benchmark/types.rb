# Per-column-type query benchmark: one scenario per MySQL type, so the cost of
# decoding each type is directly comparable between MRI mysql2 and this gem.
#
#   ruby  benchmark/types.rb     # MRI, uses the installed mysql2 gem
#   jruby benchmark/types.rb     # JRuby, uses this gem + Connector/J
#   ruby  benchmark/compare.rb   # runs both via mise and diffs them
#
# Each scenario selects BENCH_COPIES aliases of a single column over BENCH_LIMIT
# rows, so per-value decoding dominates the fixed per-query/per-row overhead (the
# `id (1 col)` scenario measures that overhead on its own). Env knobs on top of
# support.rb's BENCH_ROWS/WARMUP/TIME:
#   BENCH_LIMIT=100   rows per query
#   BENCH_COPIES=8    aliases of the column per row
#   BENCH_PREPARED=true  go through Client#prepare instead of Client#query
#   BENCH_SETUP=0     skip re-seeding (table already populated)
require_relative 'support'

LIMIT    = Integer(ENV.fetch('BENCH_LIMIT', 100))
COPIES   = Integer(ENV.fetch('BENCH_COPIES', 8))
PREPARED = ENV['BENCH_PREPARED'] == 'true'

client = Bench.client
Bench.setup!(client) unless ENV['BENCH_SETUP'] == '0'

def select(column, copies, limit)
  list = (1...copies).inject(column.dup) { |sql, n| "#{sql}, #{column} AS c#{n}" }
  "SELECT #{list} FROM #{Bench::TABLE} LIMIT #{limit}"
end

statements = {}
run = lambda do |sql|
  if PREPARED
    (statements[sql] ||= client.prepare(sql)).execute.each { |r| r }
  else
    client.query(sql).each { |r| r }
  end
end

runner = Bench::Runner.new(
  Bench.engine_label,
  "#{LIMIT} rows x #{COPIES} cols per query, #{PREPARED ? 'prepared' : 'text query'}, " \
  "table rows=#{Bench::ROWS}, warmup=#{Bench::WARMUP}s, time=#{Bench::TIME}s"
)

# the Ruby class each type decodes to -- a difference between the engines means
# the two sides aren't doing comparable work
row = client.query("SELECT * FROM #{Bench::TABLE} WHERE #{Bench::COLUMNS.map(&:name).join(' IS NOT NULL AND ')} IS NOT NULL LIMIT 1").first
classes = Bench::COLUMNS.to_h { |c| [c.label, row ? row[c.name].class.name : '?'] }

# fixed overhead: one narrow column, so ns/value is really ns/row
sql = select('id', 1, LIMIT)
runner.measure('id (1 col)', values: LIMIT) { run.call(sql) }

Bench::COLUMNS.each do |column|
  sql = select(column.name, COPIES, LIMIT)
  runner.measure(column.label, values: LIMIT * COPIES, note: classes[column.label]) { run.call(sql) }
end

runner.finish!
statements.each_value(&:close)
client.close
