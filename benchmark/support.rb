# Shared setup for the benchmark suite: loads the right mysql2 (MRI's C extension
# or this JRuby gem), connects using spec/configuration.yml, and seeds a table.
require 'yaml'
require 'json'

ROOT = File.expand_path('..', __dir__)

if RUBY_ENGINE == 'jruby'
  # Use this gem's lib and put Connector/J on the classpath before the extension
  # loads (its basicLoad resolves CJ classes, so the jar must be required first).
  $LOAD_PATH.unshift File.join(ROOT, 'lib')
  connector = ENV['RUBY_MYSQL2_CONNECTOR_JAR'] ||= File.join(ROOT, 'vendor', 'mysql-connector-j.jar')
  require connector
end

require 'mysql2'

module Bench
  ROWS   = Integer(ENV.fetch('BENCH_ROWS', 1000))
  WARMUP = Float(ENV.fetch('BENCH_WARMUP', 3))
  TIME   = Float(ENV.fetch('BENCH_TIME', 5))
  TABLE  = 'mysql2_bench'

  Column = Struct.new(:name, :ddl, :literal) do
    def label
      name.sub(/_test\z/, '')
    end
  end

  def self.sq(str)
    "'#{str}'"
  end

  def self.col(name, ddl, &literal)
    Column.new(name, ddl, literal)
  end

  # One entry per MySQL type under test: drives both the table definition and the
  # per-type scenarios in types.rb. The literal block returns SQL for row i.
  COLUMNS = [
    col('null_test',       'VARCHAR(10)')        { |i| i.even? ? 'NULL' : sq("str#{i % 100}") },
    col('tiny_int_test',   'TINYINT')            { |i| (i % 127) - 64 },
    col('small_int_test',  'SMALLINT')           { |i| (i % 32_767) - 16_384 },
    col('medium_int_test', 'MEDIUMINT')          { |i| i % 8_388_607 },
    col('int_test',        'INT')                { |i| (i * 1000) % 2_000_000_000 },
    col('big_int_test',    'BIGINT')             { |i| i * 100_000_000 },
    col('bool_test',       'TINYINT(1)')         { |i| i.even? ? 1 : 0 },
    col('float_test',      'FLOAT(10,3)')        { |i| format('%.3f', (i % 1_000_000) * 1.5) },
    col('double_test',     'DOUBLE')             { |i| format('%.6f', i * 1.000001) },
    col('decimal_test',    'DECIMAL(10,3)')      { |i| format('%.3f', (i % 1_000_000) * 3.25) },
    col('bit_test',        'BIT(8)')             { |i| "b'#{format('%08b', i % 256)}'" },
    col('date_test',       'DATE')               { |i| sq(format('2021-%02d-%02d', (i % 12) + 1, (i % 28) + 1)) },
    col('date_time_test',  'DATETIME')           { |i| sq(format('2021-06-15 %02d:%02d:%02d', i % 24, i % 60, i % 60)) },
    col('timestamp_test',  'TIMESTAMP NULL')     { |_i| sq('2021-06-15 12:00:00') },
    col('time_test',       'TIME')               { |i| sq(format('%02d:30:00', i % 24)) },
    col('year_test',       'YEAR')               { |i| 1901 + (i % 155) },
    col('char_test',       'CHAR(10)')           { |i| sq("char#{i % 100}") },
    col('varchar_test',    'VARCHAR(255)')       { |i| sq("a varchar value number #{i} with some length to it") },
    col('text_test',       'TEXT')               { |i| sq("a longer text body repeated a bit for row #{i} lorem ipsum dolor sit amet consectetur") },
    col('binary_test',     'BINARY(16)')         { |i| "x'#{format('%032x', i % (1 << 64))}'" },
    col('varbinary_test',  'VARBINARY(255)')     { |i| "x'#{format('%032x', i % (1 << 64)) * 2}'" },
    col('blob_test',       'BLOB')               { |i| "x'#{format('%032x', i % (1 << 64)) * 8}'" },
    col('enum_test',       "ENUM('a','b','c')")  { |i| sq(%w[a b c][i % 3]) },
    col('set_test',        "SET('a','b','c')")   { |i| sq(['a', 'b', 'a,b', 'a,b,c'][i % 4]) },
    col('json_test',       'JSON')               { |i| sq(%({"id":#{i},"name":"row #{i}","tags":["a","b"]})) },
  ].freeze

  module_function

  def clock
    Process.clock_gettime(Process::CLOCK_MONOTONIC)
  end

  # spec/configuration.yml if present (it is git-ignored), else the MYSQL_* env
  # vars the rake tasks use
  def credentials
    config = File.join(ROOT, 'spec', 'configuration.yml')
    return YAML.load_file(config)['root'].transform_keys(&:to_sym) if File.exist?(config)

    { host: ENV['MYSQL_HOST'] || '127.0.0.1', port: Integer(ENV['MYSQL_PORT'] || 13_306),
      username: ENV['MYSQL_USER'] || 'root', password: ENV['MYSQL_PASSWORD'] || 'root',
      database: ENV['MYSQL_DATABASE'] || 'test' }
  end

  def client(overrides = {})
    Mysql2::Client.new(credentials.merge(overrides))
  end

  def engine_label
    if RUBY_ENGINE == 'jruby'
      "JRuby #{JRUBY_VERSION} (mysql2-jruby #{Mysql2::VERSION})"
    else
      "#{RUBY_ENGINE} #{RUBY_VERSION} (mysql2 #{Mysql2::VERSION})"
    end
  end

  # Create and populate the benchmark table with deterministic, type-diverse rows.
  def setup!(c = client)
    c.query <<~SQL
      CREATE TABLE IF NOT EXISTS #{TABLE} (
        id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
        #{COLUMNS.map { |col| "#{col.name} #{col.ddl}" }.join(",\n  ")}
      ) DEFAULT CHARSET=utf8mb4
    SQL

    # a table left over from an older revision is missing the newer columns
    have = c.query("SHOW COLUMNS FROM #{TABLE}").map { |r| r['Field'] }
    missing = COLUMNS.reject { |col| have.include?(col.name) }
    unless missing.empty?
      c.query "ALTER TABLE #{TABLE} #{missing.map { |col| "ADD COLUMN #{col.name} #{col.ddl}" }.join(', ')}"
    end

    c.query "TRUNCATE #{TABLE}"

    insert = "INSERT INTO #{TABLE} (#{COLUMNS.map(&:name).join(', ')}) VALUES "
    batch = []
    (1..ROWS).each do |i|
      batch << "(#{COLUMNS.map { |col| col.literal.call(i) }.join(',')})"
      if batch.size >= 200
        c.query(insert + batch.join(','))
        batch.clear
      end
    end
    c.query(insert + batch.join(',')) unless batch.empty?
    c
  end

  # Warms up (drives JRuby's JIT), then counts iterations over a fixed wall-clock
  # window. Prints a table, or emits JSON for compare.rb when BENCH_FORMAT=json.
  class Runner
    def initialize(title = Bench.engine_label, subtitle = nil)
      @title = title
      @subtitle = subtitle
      @results = []
      @json = ENV['BENCH_FORMAT'] == 'json'
      return if @json

      puts title
      puts subtitle if subtitle
      puts
    end

    # values: number of column values materialized per iteration, for ns/value
    # note:   free-form annotation (types.rb reports the Ruby class it decodes to)
    def measure(label, values: nil, note: nil)
      $stderr.print "#{label} ... " if @json

      stop = Bench.clock + WARMUP
      yield while Bench.clock < stop

      count = 0
      start = Bench.clock
      stop = start + TIME
      while Bench.clock < stop
        yield
        count += 1
      end
      elapsed = Bench.clock - start
      ops = count / elapsed
      ns = values ? (elapsed / (count * values.to_f)) * 1e9 : nil
      @results << { 'label' => label, 'ops' => ops, 'ns_per_value' => ns, 'count' => count,
                    'elapsed' => elapsed, 'values' => values, 'note' => note }

      if @json
        $stderr.printf "%.1f ops/sec\n", ops
      else
        printf "  %-18s %10.1f ops/sec%s%s\n", label, ops,
               ns ? format('  %8.1f ns/value', ns) : '', note ? "  #{note}" : ''
      end
      ops
    end

    def finish!
      return unless @json

      payload = {
        'engine' => @title, 'ruby' => RUBY_DESCRIPTION, 'subtitle' => @subtitle, 'results' => @results
      }
      out = JSON.pretty_generate(payload)
      ENV['BENCH_OUT'] ? File.write(ENV['BENCH_OUT'], out) : puts(out)
    end
  end
end
