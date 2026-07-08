$LOAD_PATH.unshift File.expand_path('../../lib', __FILE__)
require 'mysql2'

puts "Mysql2::VERSION = #{Mysql2::VERSION}"

client = Mysql2::Client.new(
  host: '127.0.0.1', port: 13306, username: 'root', password: 'root', database: 'bench',
)
puts "server: #{client.server_info[:version]}"
puts "ping:   #{client.ping}"

puts "\n-- typed casting (cast: true) --"
row = client.query("SELECT id_val, score, ts, name, data FROM bench_data LIMIT 1").first
row.each do |k, v|
  puts format("  %-8s %-24s %s", k, v.inspect[0, 24], v.class)
end

puts "\n-- as: :array, symbolize_keys --"
r = client.query("SELECT 1 AS a, 'hi' AS b", as: :array, symbolize_keys: true).first
puts "  #{r.inspect}"

puts "\n-- cast: false (raw strings) --"
r = client.query("SELECT id_val, ts FROM bench_data LIMIT 1", cast: false).first
puts "  #{r.inspect} (#{r['id_val'].class})"

puts "\n-- aggregate + count --"
res = client.query("SELECT COUNT(*) AS n, AVG(score) AS avg_score FROM bench_data")
puts "  #{res.first.inspect}"
puts "  fields: #{res.fields.inspect}"

puts "\n-- DDL / DML: affected_rows + last_id --"
client.query("DROP TEMPORARY TABLE IF EXISTS t")
client.query("CREATE TEMPORARY TABLE t (id INT PRIMARY KEY AUTO_INCREMENT, v VARCHAR(20))")
client.query("INSERT INTO t (v) VALUES ('#{client.escape("a'b")}')")
puts "  affected_rows=#{client.affected_rows} last_id=#{client.last_id}"
client.query("INSERT INTO t (v) VALUES ('two'),('three')")
puts "  affected_rows=#{client.affected_rows} last_id=#{client.last_id}"
puts "  rows: #{client.query('SELECT * FROM t ORDER BY id').map { |h| h['v'] }.inspect}"

puts "\n-- NULL handling --"
puts "  #{client.query('SELECT NULL AS n, 42 AS i').first.inspect}"

puts "\n-- error mapping --"
begin
  client.query("SELECT * FROM no_such_table")
rescue Mysql2::Error => e
  puts "  #{e.class}: errno=#{e.error_number} sqlstate=#{e.sql_state} #{e.message[0, 40]}"
end

client.close
puts "\nclosed? #{client.closed?}"
puts "OK"
