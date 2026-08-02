$LOAD_PATH.unshift File.expand_path('../../lib', __FILE__)

require 'minitest/autorun'

require 'mysql2'

# :encoding picks the connection charset (mysql2's MYSQL_SET_CHARSET_NAME) as well as
# the encoding results are tagged with -- the two have to agree, or query text is sent
# in one encoding and read in another.
class EncodingTest < Minitest::Test
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

  def charsets(client)
    client.query('SELECT @@character_set_client c, @@character_set_connection n').first
  end

  # --- the charset reaches the connection --------------------------------------

  def test_default_encoding_is_utf8mb4
    client = new_client
    assert_equal({ 'c' => 'utf8mb4', 'n' => 'utf8mb4' }, charsets(client))
    assert_equal Encoding::UTF_8, client.encoding
  end

  def test_encoding_sets_the_connection_charset
    { 'latin1' => 'ISO-8859-1', 'ascii' => 'US-ASCII', 'sjis' => 'Shift_JIS',
      'ujis' => 'eucJP-ms', 'gbk' => 'GBK' }.each do |charset, ruby_encoding|
      client = new_client(encoding: charset)
      assert_equal({ 'c' => charset, 'n' => charset }, charsets(client),
                   "expected the server to use #{charset}")
      assert_equal Encoding.find(ruby_encoding), client.encoding
    end
  end

  # the bug this guards: query text was transcoded to :encoding while the server was
  # told the connection was UTF-8, so non-ASCII in a query got mangled
  def test_non_ascii_query_text_round_trips
    client = new_client(encoding: 'latin1')
    value = 'café'.encode('ISO-8859-1')
    result = client.query("SELECT '#{value}' AS v").first['v']
    assert_equal Encoding.find('ISO-8859-1'), result.encoding
    assert_equal 'café', result.encode('UTF-8')
  end

  # mysql2 raises for charsets it has no Ruby encoding for
  def test_unsupported_charset_raises
    err = assert_raises(Mysql2::Error) { new_client(encoding: 'swe7') }
    assert_includes err.message, "Unsupported charset: 'swe7'"
    assert_raises(Mysql2::Error) { new_client(encoding: 'not-a-charset') }
  end

  # --- escaping has to follow the connection charset ---------------------------

  # In GBK/Big5/SJIS a byte pair like 0xBF 0x5C is one character, so escaping byte by
  # byte lets input choose whether the backslash is a backslash: 0xBF 0x27 would become
  # 0xBF 0x5C 0x27, the server reads 0xBF5C as a character and the quote breaks out.
  { 'gbk' => 0xbf, 'big5' => 0xa4, 'sjis' => 0x81 }.each do |charset, lead_byte|
    define_method("test_escape_is_multibyte_safe_in_#{charset}") do
      client = new_client(encoding: charset)
      client.query('DROP TABLE IF EXISTS mysql2_mb_test')
      client.query("CREATE TABLE mysql2_mb_test (name VARCHAR(50)) CHARSET=#{charset}")
      client.query("INSERT INTO mysql2_mb_test VALUES ('alice'),('bob')")

      injection = ([lead_byte, 0x27].pack('C*') + ' OR 1=1 -- ').force_encoding(client.encoding)
      sql = "SELECT * FROM mysql2_mb_test WHERE name = '#{client.escape(injection)}'"
      rows = begin
        client.query(sql.force_encoding(client.encoding)).to_a
      rescue Mysql2::Error
        [] # a syntax error is also a non-escape
      end
      assert_empty rows, "#{charset}: escaped input broke out of the string literal"
    ensure
      client.query('DROP TABLE IF EXISTS mysql2_mb_test') rescue nil
    end
  end

  def test_escape_keeps_multibyte_values_intact
    client = new_client(encoding: 'gbk')
    value = '你好'.encode('GBK')
    result = client.query("SELECT '#{client.escape(value)}' AS v".force_encoding('GBK')).first['v']
    assert_equal '你好', result.encode('UTF-8')
  end

  def test_escape_still_escapes_quotes_in_multibyte_charsets
    client = new_client(encoding: 'gbk')
    escaped = client.escape("it's".encode('GBK'))
    result = client.query("SELECT '#{escaped}' AS v".force_encoding('GBK')).first['v']
    assert_equal "it's", result.encode('UTF-8')
  end
end
