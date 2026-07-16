$LOAD_PATH.unshift File.expand_path('../../lib', __FILE__)

require 'minitest/autorun'

require 'mysql2'

# Asserts the REQUIRE_CONNECTED / auto-reconnect behavior mysql2 gives (the upstream
# specs that cover it are excluded, see test/mysql2/excludes.txt): once the connection
# is gone, operations raise "MySQL client is not connected" unless reconnect: true, in
# which case they transparently re-establish (like libmysql's auto-reconnect).
class ReconnectTest < Minitest::Test
  CONN = {
    host: ENV['MYSQL_HOST'] || '127.0.0.1', port: (ENV['MYSQL_PORT'] || 13306).to_i,
    username: ENV['MYSQL_USER'] || 'root', password: ENV['MYSQL_PASSWORD'] || 'root',
    database: ENV['MYSQL_DATABASE'] || 'test'
  }.freeze

  def setup
    @clients = []
  end

  def teardown
    @clients.each { |c| c.close rescue nil }
  end

  def new_client(opts = {})
    client = Mysql2::Client.new(CONN.merge(opts))
    @clients << client
    client
  end

  # --- reconnect off (the default): a dead connection raises --------------------

  def test_query_after_close_raises_not_connected
    client = new_client
    client.close
    err = assert_raises(Mysql2::Error) { client.query('SELECT 1') }
    assert_includes err.message, 'MySQL client is not connected'
    assert_nil err.error_number # a bare Mysql2::Error, like mysql2's REQUIRE_CONNECTED raise
  end

  def test_server_info_after_close_raises_not_connected
    client = new_client
    client.close
    err = assert_raises(Mysql2::Error) { client.server_info }
    assert_includes err.message, 'MySQL client is not connected'
  end

  def test_select_db_after_close_raises_not_connected
    client = new_client
    client.close
    err = assert_raises(Mysql2::Error) { client.select_db(CONN[:database]) }
    assert_includes err.message, 'MySQL client is not connected'
  end

  def test_ping_after_close_is_false
    client = new_client
    client.close
    assert_equal false, client.ping
  end

  # --- reconnect: true: a dead connection is transparently re-established -------

  def test_query_after_close_reconnects
    client = new_client(reconnect: true)
    first_id = client.thread_id
    client.close
    assert_equal true, client.closed?

    row = client.query('SELECT 1 AS one').first
    assert_equal 1, row['one']
    assert_equal false, client.closed?
    refute_equal first_id, client.thread_id, 'expected a new server connection after reconnect'
  end

  def test_server_info_after_close_reconnects
    client = new_client(reconnect: true)
    client.close
    info = client.server_info
    assert_kind_of Integer, info[:id]
    refute_empty info[:version]
  end

  def test_ping_after_close_reconnects
    client = new_client(reconnect: true)
    client.close
    assert_equal true, client.ping
    assert_equal false, client.closed?
  end

  # closest to the real world: the server drops us, then the next ping reconnects
  def test_ping_reconnects_after_server_kill
    client = new_client(reconnect: true)
    killed_id = client.thread_id

    admin = new_client
    admin.query("KILL CONNECTION #{killed_id}")

    assert_equal true, client.ping
    refute_equal killed_id, client.thread_id, 'expected a new server connection after reconnect'
  end
end
