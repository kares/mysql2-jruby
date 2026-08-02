$LOAD_PATH.unshift File.expand_path('../../lib', __FILE__)

require 'minitest/autorun'

require 'mysql2'

class SmokeTest < Minitest::Test

  CLIENT_OPTS = {
    host: ENV['MYSQL_HOST'] || '127.0.0.1', port: (ENV['MYSQL_PORT'] || 13306).to_i,
    username: ENV['MYSQL_USER'] || 'root', password: ENV['MYSQL_PASSWORD'] || 'root',
    database: ENV['MYSQL_DATABASE'] || 'test'
  }.freeze

  def setup
    @client = Mysql2::Client.new(**CLIENT_OPTS)
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

  # Without FOUND_ROWS (mysql2's default) affected_rows counts rows *changed*,
  # so a no-op UPDATE reports 0
  def test_affected_rows_defaults_to_changed_rows
    @client.query 'CREATE TEMPORARY TABLE found_rows_test (id INT PRIMARY KEY, v INT)'
    @client.query 'INSERT INTO found_rows_test VALUES (1, 100), (2, 100)'
    @client.query 'UPDATE found_rows_test SET v = 100 WHERE v = 100' # matches 2, changes 0
    assert_equal 0, @client.affected_rows
  end

  # flags: FOUND_ROWS makes affected_rows count rows *matched* - same no-op UPDATE reports 2
  def test_affected_rows_with_found_rows_flag_counts_matched_rows
    client = Mysql2::Client.new(CLIENT_OPTS.merge(flags: Mysql2::Client::FOUND_ROWS))
    client.query 'CREATE TEMPORARY TABLE found_rows_flag_test (id INT PRIMARY KEY, v INT)'
    client.query 'INSERT INTO found_rows_flag_test VALUES (1, 100), (2, 100)'
    client.query 'UPDATE found_rows_flag_test SET v = 100 WHERE v = 100' # matches 2, changes 0
    assert_equal 2, client.affected_rows
  ensure
    client&.close
  end

  # :url overrides the host/port/database-built JDBC URL (activerecord-jdbc-adapter style)
  def test_connect_via_url_override
    host = CLIENT_OPTS.fetch(:host)
    port = CLIENT_OPTS.fetch(:port)
    db   = CLIENT_OPTS.fetch(:database)
    url  = "jdbc:mysql://#{host}:#{port}/#{db}?useSSL=false&allowPublicKeyRetrieval=true"
    client = Mysql2::Client.new(username: CLIENT_OPTS.fetch(:username),
                                password: CLIENT_OPTS.fetch(:password),
                                url: url)
    assert_equal 1, client.query('SELECT 1 AS one').first['one']
  ensure
    client&.close
  end

  # activerecord-jdbc-adapter parity: :properties passes arbitrary Connector/J driver
  # properties through verbatim (here sessionVariables runs SET SESSION on connect)
  def test_jdbc_properties_passthrough
    client = Mysql2::Client.new(CLIENT_OPTS.merge(properties: { sessionVariables: 'wait_timeout=1234' }))
    assert_equal 1234, client.query('SELECT @@wait_timeout AS t').first['t']
  ensure
    client&.close
  end

  def test_database_and_select_db
    assert_equal CLIENT_OPTS[:database], @client.database
    @client.select_db('mysql')
    assert_equal 'mysql', @client.database
    assert_equal 'mysql', @client.query('SELECT DATABASE() AS d').first['d']
  end

  # databaseTerm changes what getCatalog() means, so #database has to read the
  # database directly -- as #select_db already sets it directly
  def test_database_with_schema_database_term
    client = Mysql2::Client.new(CLIENT_OPTS.merge(properties: { databaseTerm: 'SCHEMA' }))
    assert_equal CLIENT_OPTS[:database], client.database
  ensure
    client&.close
  end

  # activerecord-jdbc-adapter parity: :collation maps to the connectionCollation property
  def test_collation_option
    client = Mysql2::Client.new(CLIENT_OPTS.merge(collation: 'utf8mb4_unicode_ci'))
    assert_equal 'utf8mb4_unicode_ci', client.query('SELECT @@collation_connection AS c').first['c']
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
