$LOAD_PATH.unshift File.expand_path('../../lib', __FILE__)

require 'minitest/autorun'

require 'bigdecimal'
require 'date'

require 'mysql2'

class CastTest < Minitest::Test
  def setup
    @client = Mysql2::Client.new(host: ENV['MYSQL_HOST'] || '127.0.0.1', port: (ENV['MYSQL_PORT'] || 13306).to_i,
                                 username: ENV['MYSQL_USER'] || 'root', password: ENV['MYSQL_ROOT_PASSWORD'] || 'root',
                                 database: ENV['MYSQL_DATABASE'] || 'test')
  end

  def teardown
    @client&.close
  end

  # Casting must produce both the right value and the right class.
  def assert_cast(expected, actual)
    assert_equal expected, actual
    assert_equal expected.class, actual.class,
                 "expected #{expected.class}, got #{actual.class} for #{actual.inspect}"
  end

  def test_numeric
    r = @client.query("SELECT CAST(-42 AS SIGNED) i, CAST(18446744073709551615 AS UNSIGNED) big, " \
                      "CAST('3.14' AS DECIMAL(10,2)) dc, CAST('99' AS DECIMAL(4,0)) dc0, " \
                      "1.5e0 flt, 0.0e0 zero").first
    assert_cast(-42, r['i'])
    assert_cast(18_446_744_073_709_551_615, r['big']) # unsigned BIGINT -> Bignum
    assert_cast(BigDecimal('3.14'), r['dc'])
    assert_cast(99, r['dc0'])                          # DECIMAL scale 0 -> Integer
    assert_cast(1.5, r['flt'])
    assert_cast(0.0, r['zero'])
  end

  def test_temporal_utc
    r = @client.query("SELECT DATE '2024-03-09' d, TIME '13:24:35.500000' t, " \
                      "TIMESTAMP '2024-03-09 13:24:35.750000' ts", database_timezone: :utc).first
    assert_cast(Date.new(2024, 3, 9), r['d'])
    assert_cast(Time.utc(2000, 1, 1, 13, 24, 35, 500_000), r['t'])
    assert_cast(Time.utc(2024, 3, 9, 13, 24, 35, 750_000), r['ts'])
  end

  def test_boolean_casting
    @client.query("DROP TEMPORARY TABLE IF EXISTS bt")
    @client.query("CREATE TEMPORARY TABLE bt (b BIT(1), t TINYINT(1))")
    @client.query("INSERT INTO bt VALUES (b'1', 1), (b'0', 0)")
    rows = @client.query("SELECT b, t FROM bt", cast_booleans: true).to_a
    assert_equal true,  rows[0]['b']
    assert_equal false, rows[1]['b']
    assert_equal true,  rows[0]['t']
    assert_equal false, rows[1]['t']
  end

  def test_null_temporal
    r = @client.query("SELECT CAST(NULL AS DATETIME) ts").first
    assert_nil r['ts']
  end

  def test_null_in_row
    r = @client.query("SELECT NULL AS n, 42 AS i").first
    assert_equal({ 'n' => nil, 'i' => 42 }, r)
  end

  # cast: false yields every non-NULL value as a raw String, uncast.
  def test_cast_false_raw_strings
    r = @client.query("SELECT CAST(42 AS SIGNED) i, TIMESTAMP '2024-03-09 13:24:35' ts",
                      cast: false).first
    assert_cast('42', r['i'])
    assert_cast('2024-03-09 13:24:35', r['ts'])
  end

  def test_as_array
    r = @client.query("SELECT 1 AS a, 'hi' AS b", as: :array).first
    assert_equal [1, 'hi'], r
  end

  def test_symbolize_keys
    r = @client.query("SELECT 1 AS a, 'hi' AS b", symbolize_keys: true).first
    assert_equal({ a: 1, b: 'hi' }, r)
  end
end
