# ActiveRecord-level benchmark: the same comparison as types.rb, one layer up.
# Rails' stock mysql2 adapter drives the C extension on CRuby and this gem on
# JRuby, so it is a sanity check that the driver-level wins survive AR's own
# overhead (attribute types, model instantiation, the query cache, ...).
#
#   ruby  benchmark/active_record.rb
#   ruby  benchmark/compare.rb active_record.rb
#
# Both engines need the same ActiveRecord (`gem install activerecord -v 8.0.5`),
# otherwise the comparison measures AR versions rather than drivers. Env knobs on
# top of support.rb's, and BENCH_SETUP=0 to skip re-seeding:
#   AR_VERSION=8.0.5              ActiveRecord version to activate
#   AR_PREPARED_STATEMENTS=true   turn on the adapter's prepared statements
#   BENCH_WRITES=25               write operations batched per transaction
#
# AR is far more Ruby code than the driver, so JRuby needs a much longer warmup
# here than in types.rb -- BENCH_WARMUP=15 or more, otherwise the scenarios just
# rank themselves by how far the JIT had got when each one started.
require_relative 'support'

# lets the adapter's `gem "mysql2", "~> 0.5"` resolve to this gem, which is
# loaded off $LOAD_PATH rather than installed under that name
require 'mysql2/compat' if RUBY_ENGINE == 'jruby'

AR_VERSION = ENV.fetch('AR_VERSION', '8.0.5')
gem 'activerecord', AR_VERSION
require 'active_record'

PREPARED = ENV['AR_PREPARED_STATEMENTS'] == 'true'

Bench.setup!(Bench.client) unless ENV['BENCH_SETUP'] == '0'

ActiveRecord::Base.establish_connection(
  Bench.credentials.merge(adapter: 'mysql2', prepared_statements: PREPARED)
)

class BenchRow < ActiveRecord::Base
  self.table_name = Bench::TABLE
end

# a warm connection and schema, so the first measured iteration isn't the one
# paying for the column lookup
BenchRow.first
seeded_max = BenchRow.maximum(:id)
mid = BenchRow.where('id <= ?', seeded_max).limit(1).offset(Bench::ROWS / 2).pick(:id)

runner = Bench::Runner.new(
  Bench.engine_label,
  "ActiveRecord #{ActiveRecord::VERSION::STRING}, #{PREPARED ? 'prepared statements' : 'unprepared'}, " \
  "table rows=#{Bench::ROWS}, warmup=#{Bench::WARMUP}s, time=#{Bench::TIME}s"
)

# reads
runner.measure('find by pk')        { BenchRow.find(mid) }
runner.measure('where.first')       { BenchRow.where(char_test: 'char50').first }
runner.measure('load 100 models')   { BenchRow.limit(100).to_a }
runner.measure('load + read attrs') { BenchRow.limit(100).to_a.each { |r| r.int_test; r.varchar_test } }
runner.measure('load + temporals')  { BenchRow.limit(100).to_a.each { |r| r.date_test; r.date_time_test } }
runner.measure('load + decimals')   { BenchRow.limit(100).to_a.each { |r| r.decimal_test; r.float_test } }
runner.measure('pluck 100')         { BenchRow.limit(100).pluck(:int_test) }
runner.measure('pluck 100 (3 col)') { BenchRow.limit(100).pluck(:int_test, :varchar_test, :date_time_test) }
runner.measure('count')             { BenchRow.count }

# writes -- these also exercise the last_id / affected_rows path. One commit per
# row would just measure InnoDB's fsync (4-7ms here, i.e. the server and its disk,
# swamping any driver difference), so amortize it over a batch per transaction.
WRITES = Integer(ENV.fetch('BENCH_WRITES', 25))

runner.measure("insert x#{WRITES}") do
  BenchRow.transaction { WRITES.times { BenchRow.create!(int_test: 42, varchar_test: 'inserted') } }
end
row = BenchRow.create!(int_test: 0)
counter = 0
runner.measure("update x#{WRITES}") do
  BenchRow.transaction { WRITES.times { row.update!(int_test: counter += 1) } }
end

runner.finish!

BenchRow.where('id > ?', seeded_max).delete_all # leave the seeded table as we found it
ActiveRecord::Base.connection_handler.clear_all_connections!
