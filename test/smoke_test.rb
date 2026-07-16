$LOAD_PATH.unshift File.expand_path('../../lib', __FILE__)

require 'minitest/autorun'

require 'mysql2'

class SmokeTest < Minitest::Test
  def setup
    @client = Mysql2::Client.new(host: ENV['MYSQL_HOST'] || '127.0.0.1', port: (ENV['MYSQL_PORT'] || 13306).to_i,
                                 username: ENV['MYSQL_USER'] || 'root', password: ENV['MYSQL_PASSWORD'] || 'root',
                                 database: ENV['MYSQL_DATABASE'] || 'test')
  end

  def teardown
    @client&.close
  end

  def test_version
    refute_nil Mysql2::VERSION
  end

  def test_server_info
    info = @client.server_info
    assert_kind_of Integer, info[:id]
    assert_kind_of String, info[:version]
    refute_empty info[:version]
  end

  def test_ping
    assert_equal true, @client.ping
  end

  # :url overrides the host/port/database-built JDBC URL (activerecord-jdbc-adapter style)
  def test_connect_via_url_override
    host = ENV['MYSQL_HOST'] || '127.0.0.1'
    port = (ENV['MYSQL_PORT'] || 13306).to_i
    db   = ENV['MYSQL_DATABASE'] || 'test'
    url  = "jdbc:mysql://#{host}:#{port}/#{db}?useSSL=false&allowPublicKeyRetrieval=true"
    client = Mysql2::Client.new(username: ENV['MYSQL_USER'] || 'root',
                                password: ENV['MYSQL_PASSWORD'] || 'root', url: url)
    assert_equal 1, client.query('SELECT 1 AS one').first['one']
  ensure
    client&.close
  end

  def test_fields_and_aggregate
    res = @client.query("SELECT COUNT(*) AS n, AVG(x) AS avg_x FROM (SELECT 1 AS x UNION SELECT 3) t")
    assert_equal %w[n avg_x], res.fields
    row = res.first
    assert_equal 2, row['n']
    assert_in_delta 2.0, row['avg_x'].to_f
  end

  def test_dml_affected_rows_and_last_id
    @client.query("DROP TEMPORARY TABLE IF EXISTS t")
    @client.query("CREATE TEMPORARY TABLE t (id INT PRIMARY KEY AUTO_INCREMENT, v VARCHAR(20))")

    @client.query("INSERT INTO t (v) VALUES ('#{@client.escape("a'b")}')")
    assert_equal 1, @client.affected_rows
    assert_equal 1, @client.last_id

    @client.query("INSERT INTO t (v) VALUES ('two'),('three')")
    assert_equal 2, @client.affected_rows
    assert_equal 2, @client.last_id # LAST_INSERT_ID is the first id of a multi-row insert

    vals = @client.query("SELECT v FROM t ORDER BY id").map { |h| h['v'] }
    assert_equal ["a'b", 'two', 'three'], vals
  end

  def test_query_without_result_returns_nil
    assert_nil @client.query("DROP TEMPORARY TABLE IF EXISTS does_not_exist_here")
  end

  def test_error_mapping
    err = assert_raises(Mysql2::Error) { @client.query("SELECT * FROM no_such_table") }
    assert_equal 1146, err.error_number
    assert_equal '42S02', err.sql_state
  end

  def test_close
    assert_equal false, @client.closed?
    @client.close
    assert_equal true, @client.closed?
  end
end
