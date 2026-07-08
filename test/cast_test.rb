$LOAD_PATH.unshift File.expand_path('../../lib', __FILE__)
require 'mysql2'
require 'bigdecimal'
require 'date'

c = Mysql2::Client.new(host: '127.0.0.1', port: 13306, username: 'root', password: 'root', database: 'bench')

def check(label, actual, expected)
  ok = actual == expected && actual.class == expected.class
  printf("  [%s] %-16s => %-28s (%s)\n", ok ? 'ok' : 'XX', label, actual.inspect[0, 28], actual.class)
  ok
end

fails = 0

puts "-- numeric --"
r = c.query("SELECT CAST(-42 AS SIGNED) i, CAST(18446744073709551615 AS UNSIGNED) big, " \
            "CAST('3.14' AS DECIMAL(10,2)) dc, CAST('99' AS DECIMAL(4,0)) dc0, " \
            "1.5e0 flt, 0.0e0 zero").first
fails += 1 unless check('signed', r['i'], -42)
fails += 1 unless check('unsigned big', r['big'], 18_446_744_073_709_551_615) # Bignum
fails += 1 unless check('decimal', r['dc'], BigDecimal('3.14'))
fails += 1 unless check('decimal scale0', r['dc0'], 99)
fails += 1 unless check('float', r['flt'], 1.5)
fails += 1 unless check('float zero', r['zero'], 0.0)

puts "-- temporal (database_timezone: :utc) --"
r = c.query("SELECT DATE '2024-03-09' d, TIME '13:24:35.500000' t, " \
            "TIMESTAMP '2024-03-09 13:24:35.750000' ts", database_timezone: :utc).first
fails += 1 unless check('date', r['d'], Date.new(2024, 3, 9))
fails += 1 unless check('time', r['t'], Time.utc(2000, 1, 1, 13, 24, 35, 500_000))
fails += 1 unless check('datetime', r['ts'], Time.utc(2024, 3, 9, 13, 24, 35, 750_000))

puts "-- boolean casting (cast_booleans) --"
r = c.query("SELECT CAST(1 AS UNSIGNED) t1, 0 f0 FROM dual", cast_booleans: true).first
# NB: plain integer literals aren't tinyint(1); use an explicit tinyint column
c.query("DROP TEMPORARY TABLE IF EXISTS bt")
c.query("CREATE TEMPORARY TABLE bt (b BIT(1), t TINYINT(1))")
c.query("INSERT INTO bt VALUES (b'1', 1), (b'0', 0)")
rows = c.query("SELECT b, t FROM bt", cast_booleans: true).to_a
fails += 1 unless check('bit true',   rows[0]['b'], true)
fails += 1 unless check('bit false',  rows[1]['b'], false)
fails += 1 unless check('tiny true',  rows[0]['t'], true)
fails += 1 unless check('tiny false', rows[1]['t'], false)

puts "-- NULL temporal --"
r = c.query("SELECT CAST(NULL AS DATETIME) ts").first
fails += 1 unless check('null datetime', r['ts'], nil)

c.close
puts(fails.zero? ? "\nALL PASS" : "\n#{fails} FAILURE(S)")
exit(fails.zero? ? 0 : 1)
