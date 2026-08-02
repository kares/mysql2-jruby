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

  def test_database_after_close_raises_not_connected
    client = new_client
    client.close
    err = assert_raises(Mysql2::Error) { client.database }
    assert_includes err.message, 'MySQL client is not connected'
  end

  # #connect is overridable (mysql2's suite stubs it out), so the connection may
  # never have been established at all -- that must raise too, not NPE
  def test_database_when_never_connected_raises_not_connected
    stubbed = Class.new(Mysql2::Client) { def connect(*args) = self }
    err = assert_raises(Mysql2::Error) { stubbed.new(CONN).database }
    assert_includes err.message, 'MySQL client is not connected'
  end

  def test_ping_after_close_is_false
    client = new_client
    client.close
    assert_equal false, client.ping
  end

  # --- reconnect: true: an explicit #close still disables reconnect ------------
  # (mysql2's nogvl_close clears reconnect_enabled; reconnect revives a *dropped*
  # connection, never a deliberately closed one)

  def test_query_after_close_raises_even_with_reconnect
    client = new_client(reconnect: true)
    client.close
    err = assert_raises(Mysql2::Error) { client.query('SELECT 1') }
    assert_includes err.message, 'MySQL client is not connected'
  end

  def test_server_info_after_close_raises_even_with_reconnect
    client = new_client(reconnect: true)
    client.close
    assert_raises(Mysql2::Error) { client.server_info }
  end

  def test_ping_after_close_is_false_even_with_reconnect
    client = new_client(reconnect: true)
    client.close
    assert_equal false, client.ping
  end

  # closest to the real world: the server *drops* us (not an explicit close), then
  # the next ping transparently re-establishes the connection
  def test_ping_reconnects_after_server_kill
    client = new_client(reconnect: true)
    killed_id = client.thread_id

    admin = new_client
    admin.query("KILL CONNECTION #{killed_id}")

    assert_equal true, client.ping
    refute_equal killed_id, client.thread_id, 'expected a new server connection after reconnect'
  end

  # --- re-connecting must not strand the connection it replaces ----------------
  # (the server keeps the thread of an abandoned connection until the process exits)

  def test_reconnect_closes_the_replaced_connection
    client = new_client
    replaced_id = client.thread_id

    client.send(:connect)

    refute_equal replaced_id, client.thread_id
    assert_equal 0, server_threads(replaced_id), 'replaced connection was left open on the server'
  end

  def test_repeated_reconnects_do_not_accumulate_server_threads
    client = new_client
    ids = [client.thread_id]
    5.times do
      client.send(:connect)
      ids << client.thread_id
    end

    assert_equal 6, ids.uniq.size, 'expected a distinct server connection each time'
    stranded = ids[0...-1].count { |id| server_threads(id) > 0 }
    assert_equal 0, stranded, "#{stranded} of #{ids.size - 1} replaced connections left open"
    assert_equal 1, client.query('SELECT 1 AS v').first['v'] # still usable
  end

  private

  # rows in the server's process list for a connection id (0 once it is really gone)
  def server_threads(thread_id)
    admin = @admin ||= new_client
    admin.query("SELECT COUNT(*) c FROM information_schema.processlist WHERE id = #{thread_id}")
         .first['c'].to_i
  end
end
