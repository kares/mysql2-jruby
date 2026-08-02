$LOAD_PATH.unshift File.expand_path('../../lib', __FILE__)

require 'minitest/autorun'

require 'mysql2'

# Client#escape follows mysql_real_escape_string: which escaping applies depends on the
# server's *current* sql_mode. Under NO_BACKSLASH_ESCAPES a backslash is a literal, so
# escaping a quote with one leaves the quote closing the literal -- an injection point.
class EscapeTest < Minitest::Test
  CONN = {
    host: ENV['MYSQL_HOST'] || '127.0.0.1', port: (ENV['MYSQL_PORT'] || 13306).to_i,
    username: ENV['MYSQL_USER'] || 'root', password: ENV['MYSQL_PASSWORD'] || 'root',
    database: ENV['MYSQL_DATABASE'] || 'test'
  }.freeze

  def setup
    @client = Mysql2::Client.new(CONN)
  end

  def teardown
    @client.close rescue nil
  end

  def no_backslash_escapes!
    @client.query("SET SESSION sql_mode = CONCAT(@@sql_mode, ',NO_BACKSLASH_ESCAPES')")
  end

  # --- default sql_mode: backslash escaping ------------------------------------

  def test_escape_uses_backslashes
    assert_equal "abc\\'def\\\"ghi\\0jkl%mno", @client.escape("abc'def\"ghi\0jkl%mno")
  end

  def test_escape_returns_same_object_when_nothing_to_escape
    str = 'nothing to escape'
    assert_same str, @client.escape(str)
  end

  def test_escaped_value_round_trips
    assert_equal "it's", select_literal(@client.escape("it's"))
  end

  # --- NO_BACKSLASH_ESCAPES: the quote is doubled instead ----------------------

  def test_escape_doubles_quote_under_no_backslash_escapes
    no_backslash_escapes!
    assert_equal "it''s", @client.escape("it's")
  end

  # only the quote is special in this mode (MySQL's escape_quotes_for_mysql)
  def test_escape_leaves_backslash_and_control_chars_under_no_backslash_escapes
    no_backslash_escapes!
    assert_equal 'a\\b', @client.escape('a\\b')
    assert_equal "a\nb", @client.escape("a\nb")
    assert_equal "a\0b", @client.escape("a\0b")
  end

  def test_escape_returns_same_object_when_nothing_to_escape_under_no_backslash_escapes
    no_backslash_escapes!
    str = 'nothing to escape'
    assert_same str, @client.escape(str)
  end

  def test_escaped_value_round_trips_under_no_backslash_escapes
    no_backslash_escapes!
    assert_equal "it's", select_literal(@client.escape("it's"))
  end

  # sql_mode can change mid-connection, so the mode is re-read per call
  def test_escape_follows_sql_mode_changed_mid_connection
    assert_equal "it\\'s", @client.escape("it's")
    no_backslash_escapes!
    assert_equal "it''s", @client.escape("it's")
    @client.query("SET SESSION sql_mode = REPLACE(@@sql_mode, 'NO_BACKSLASH_ESCAPES', '')")
    assert_equal "it\\'s", @client.escape("it's")
  end

  # the injection this guards: a backslash-escaped quote would close the literal,
  # leaving the rest of the input to be parsed as SQL
  def test_escape_prevents_injection_under_no_backslash_escapes
    no_backslash_escapes!
    @client.query('DROP TABLE IF EXISTS mysql2_escape_test')
    @client.query('CREATE TABLE mysql2_escape_test (name VARCHAR(50))')
    @client.query("INSERT INTO mysql2_escape_test VALUES ('alice'),('bob')")

    injection = "\\' OR 1=1 -- "
    rows = @client.query(
      "SELECT * FROM mysql2_escape_test WHERE name = '#{@client.escape(injection)}'"
    ).to_a
    assert_empty rows, 'escaped input must not break out of the string literal'
  ensure
    @client.query('DROP TABLE IF EXISTS mysql2_escape_test') rescue nil
  end

  # --- Mysql2::Client.escape (mysql_escape_string) -----------------------------

  # the class method has no connection, so it can't consult sql_mode and always
  # escapes with backslashes
  def test_class_level_escape_always_uses_backslashes
    no_backslash_escapes!
    assert_equal "it\\'s", Mysql2::Client.escape("it's")
  end

  private

  def select_literal(escaped)
    @client.query("SELECT '#{escaped}' AS v").first['v']
  end
end
