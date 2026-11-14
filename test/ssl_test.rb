$LOAD_PATH.unshift File.expand_path('../../lib', __FILE__)

require 'minitest/autorun'
require 'tmpdir'
require 'fileutils'
require 'openssl'

require 'mysql2'

class SslTest < Minitest::Test

  CLIENT_OPTS = {
    host: ENV['MYSQL_HOST'] || '127.0.0.1', port: (ENV['MYSQL_PORT'] || 13306).to_i,
    username: ENV['MYSQL_USER'] || 'root', password: ENV['MYSQL_PASSWORD'] || 'root',
    database: ENV['MYSQL_DATABASE'] || 'test'
  }.freeze

  def setup
    @client = Mysql2::Client.new(**CLIENT_OPTS)
    @server_certs ||= fetch_server_certs
  end

  def teardown
    @client&.close
  end

  # --- 1. Default (non-SSL) behavior & #ssl_cipher -------------------------

  def test_default_connection_has_ssl_disabled
    assert_nil @client.ssl_cipher
    status = server_ssl_status(@client)
    assert_equal '', status['Ssl_cipher']
  end

  def test_ssl_cipher_returns_nil_when_closed
    client = Mysql2::Client.new(**CLIENT_OPTS, ssl_mode: :required)
    refute_nil client.ssl_cipher
    client.close
    assert_nil client.ssl_cipher
  end

  # --- 2. :ssl_mode variants (Symbols, Strings, Constants) -----------------

  def test_ssl_mode_disabled
    [
      :disabled,
      'disabled',
      'DISABLED',
      'ssl_mode_disabled',
      'SSL_MODE_DISABLED',
      Mysql2::Client::SSL_MODE_DISABLED
    ].each do |mode|
      client = Mysql2::Client.new(**CLIENT_OPTS, ssl_mode: mode)
      assert_nil client.ssl_cipher, "expected nil ssl_cipher for ssl_mode: #{mode.inspect}"
      assert_equal '', server_ssl_status(client)['Ssl_cipher']
      client.close
    end
  end

  def test_ssl_mode_preferred_and_required
    [
      :preferred,
      :required,
      'REQUIRED',
      'SSL_MODE_REQUIRED',
      Mysql2::Client::SSL_MODE_PREFERRED,
      Mysql2::Client::SSL_MODE_REQUIRED
    ].each do |mode|
      client = Mysql2::Client.new(**CLIENT_OPTS, ssl_mode: mode)
      status = server_ssl_status(client)
      refute_empty status['Ssl_cipher'], "expected non-empty Ssl_cipher for ssl_mode: #{mode.inspect}"
      refute_empty status['Ssl_version']
      assert_equal status['Ssl_cipher'], client.ssl_cipher
      client.close
    end
  end

  def test_ssl_mode_unknown_symbol_warns_and_falls_back
    _out, err = capture_io do
      client = Mysql2::Client.new(**CLIENT_OPTS, ssl_mode: :verifa_ca)
      assert_nil client.ssl_cipher
      client.close
    end
    assert_match(/Unknown MySQL ssl_mode flag: verifa_ca/, err)
  end

  def test_ssl_mode_invalid_integer_raises
    err = assert_raises(Mysql2::Error) do
      Mysql2::Client.new(**CLIENT_OPTS, ssl_mode: 99)
    end
    assert_match(/ssl_mode= takes DISABLED, PREFERRED, REQUIRED, VERIFY_CA, VERIFY_IDENTITY, you passed: 99/, err.message)
  end

  # --- 3. :flags SSL and :sslcipher ----------------------------------------

  def test_flags_ssl_enables_tls
    [
      Mysql2::Client::SSL,
      ['SSL'],
      'SSL'
    ].each do |flags|
      client = Mysql2::Client.new(**CLIENT_OPTS, flags: flags)
      status = server_ssl_status(client)
      refute_empty status['Ssl_cipher']
      assert_equal status['Ssl_cipher'], client.ssl_cipher
      client.close
    end
  end

  def test_sslcipher_with_openssl_and_iana_names
    # OpenSSL cipher name (TLS 1.2 DHE-RSA-AES256-SHA, as used in upstream mysql2 specs)
    client = Mysql2::Client.new(**CLIENT_OPTS, sslcipher: 'DHE-RSA-AES256-SHA')
    status = server_ssl_status(client)
    assert_equal 'DHE-RSA-AES256-SHA', status['Ssl_cipher']
    assert_equal 'DHE-RSA-AES256-SHA', client.ssl_cipher
    client.close

    # IANA / JSSE cipher name (TLS 1.3)
    client2 = Mysql2::Client.new(**CLIENT_OPTS, sslcipher: 'TLS_AES_128_GCM_SHA256')
    status2 = server_ssl_status(client2)
    assert_equal 'TLS_AES_128_GCM_SHA256', status2['Ssl_cipher']
    assert_equal 'TLS_AES_128_GCM_SHA256', client2.ssl_cipher
    client2.close
  end

  # --- 4. PEM certificates (:sslca, :sslcapath, :sslcert, :sslkey) ---------

  def test_pem_sslca_verifies_server_certificate
    skip 'server CA PEM not available' unless @server_certs[:ca_pem]

    Dir.mktmpdir('mysql2-ssl-ca') do |dir|
      ca_file = File.join(dir, 'ca.pem')
      File.write(ca_file, @server_certs[:ca_pem])

      # Passing :sslca alone implies VERIFY_CA and trusts the server's CA
      client = Mysql2::Client.new(**CLIENT_OPTS, sslca: ca_file)
      status = server_ssl_status(client)
      refute_empty status['Ssl_cipher']
      assert_equal status['Ssl_cipher'], client.ssl_cipher
      client.close
    end
  end

  def test_pem_sslca_rejects_untrusted_ca
    # Using upstream mysql2's test CA (which did NOT sign the running server's cert)
    untrusted_ca = File.expand_path('../../mysql2/spec/ssl/ca-cert.pem', __FILE__)
    skip 'upstream spec/ssl/ca-cert.pem not found' unless File.exist?(untrusted_ca)

    assert_raises(Mysql2::Error::ConnectionError) do
      Mysql2::Client.new(**CLIENT_OPTS, sslca: untrusted_ca, ssl_mode: :verify_ca)
    end

    # When ssl_mode: :required (or sslverify: false) is explicitly set, CA mismatch is not enforced
    client = Mysql2::Client.new(**CLIENT_OPTS, sslca: untrusted_ca, sslverify: false)
    refute_nil client.ssl_cipher
    client.close
  end

  def test_pem_sslcapath_directory
    skip 'server CA PEM not available' unless @server_certs[:ca_pem]

    Dir.mktmpdir('mysql2-ssl-capath') do |dir|
      File.write(File.join(dir, 'server-ca.pem'), @server_certs[:ca_pem])

      client = Mysql2::Client.new(**CLIENT_OPTS, sslcapath: dir)
      refute_nil client.ssl_cipher
      client.close
    end
  end

  def test_verify_identity_checks_hostname
    skip 'server CA PEM not available' unless @server_certs[:ca_pem]

    Dir.mktmpdir('mysql2-ssl-id') do |dir|
      ca_file = File.join(dir, 'ca.pem')
      File.write(ca_file, @server_certs[:ca_pem])

      # Server's auto-generated cert has CN=MySQL_Server_*_Auto_Generated_Server_Certificate,
      # which does NOT match 127.0.0.1 -> VERIFY_IDENTITY / sslverify: true must reject it!
      assert_raises(Mysql2::Error::ConnectionError) do
        Mysql2::Client.new(**CLIENT_OPTS, sslca: ca_file, ssl_mode: :verify_identity)
      end

      assert_raises(Mysql2::Error::ConnectionError) do
        Mysql2::Client.new(**CLIENT_OPTS, sslca: ca_file, sslverify: true)
      end
    end
  end

  def test_pem_client_cert_and_key_pkcs8_and_pkcs1
    skip 'server client cert/key PEM not available' unless @server_certs[:client_cert_pem] && @server_certs[:client_key_pem]

    Dir.mktmpdir('mysql2-client-pem') do |dir|
      cert_file = File.join(dir, 'client-cert.pem')
      key_file  = File.join(dir, 'client-key.pem')
      File.write(cert_file, @server_certs[:client_cert_pem])
      File.write(key_file, @server_certs[:client_key_pem])

      # 1. PKCS#8 private key (-----BEGIN PRIVATE KEY-----)
      client = Mysql2::Client.new(
        **CLIENT_OPTS,
        sslkey: key_file,
        sslcert: cert_file,
        ssl_mode: :required
      )
      refute_nil client.ssl_cipher
      client.close

      # 2. PKCS#1 RSA private key (-----BEGIN RSA PRIVATE KEY-----)
      rsa_key = OpenSSL::PKey::RSA.new(@server_certs[:client_key_pem])
      pkcs1_pem = rsa_key.to_pem
      unless pkcs1_pem.include?('BEGIN RSA PRIVATE KEY')
        pkcs1_der = rsa_key.to_der
        pkcs1_pem = "-----BEGIN RSA PRIVATE KEY-----\n" + [pkcs1_der].pack('m64') + "-----END RSA PRIVATE KEY-----\n"
      end
      pkcs1_file = File.join(dir, 'client-pkcs1-key.pem')
      File.write(pkcs1_file, pkcs1_pem)

      client2 = Mysql2::Client.new(
        **CLIENT_OPTS,
        sslkey: pkcs1_file,
        sslcert: cert_file,
        ssl_mode: :required
      )
      refute_nil client2.ssl_cipher
      client2.close
    end
  end

  # --- 5. Java KeyStore (.p12) compatibility (AR-JDBC style) ---------------

  def test_pkcs12_keystore_and_truststore_files
    skip 'server certs not available' unless @server_certs[:ca_pem] && @server_certs[:client_cert_pem] && @server_certs[:client_key_pem]

    Dir.mktmpdir('mysql2-p12') do |dir|
      # Build a PKCS12 truststore with the server CA
      ca_cert = OpenSSL::X509::Certificate.new(@server_certs[:ca_pem])
      trust_p12_path = File.join(dir, 'truststore.p12')
      ks = java.security.KeyStore.getInstance('PKCS12')
      ks.load(nil, 'changeit'.to_java.toCharArray)
      cf = java.security.cert.CertificateFactory.getInstance('X.509')
      jcert = cf.generateCertificate(java.io.ByteArrayInputStream.new(ca_cert.to_der.to_java_bytes))
      ks.setCertificateEntry('ca', jcert)
      fos = java.io.FileOutputStream.new(trust_p12_path)
      ks.store(fos, 'changeit'.to_java.toCharArray)
      fos.close

      # Build a PKCS12 client keystore using Java KeyStore
      client_cert = cf.generateCertificate(java.io.ByteArrayInputStream.new(OpenSSL::X509::Certificate.new(@server_certs[:client_cert_pem]).to_der.to_java_bytes))
      b64 = @server_certs[:client_key_pem].lines.reject { |l| l.strip.start_with?('-----') }.join.gsub(/\s+/, '')
      der = b64.unpack1('m0').to_java_bytes
      priv_key = java.security.KeyFactory.getInstance('RSA').generatePrivate(java.security.spec.PKCS8EncodedKeySpec.new(der))
      cks = java.security.KeyStore.getInstance('PKCS12')
      cks.load(nil, 'changeit'.to_java.toCharArray)
      cert_arr = [client_cert].to_java(java.security.cert.Certificate)
      cks.setKeyEntry('client', priv_key, 'changeit'.to_java.toCharArray, cert_arr)
      client_p12_path = File.join(dir, 'client.p12')
      cfos = java.io.FileOutputStream.new(client_p12_path)
      cks.store(cfos, 'changeit'.to_java.toCharArray)
      cfos.close

      client = Mysql2::Client.new(
        **CLIENT_OPTS,
        sslca: trust_p12_path,
        sslcert: client_p12_path,
        properties: {
          trustCertificateKeyStorePassword: 'changeit',
          clientCertificateKeyStorePassword: 'changeit'
        }
      )
      refute_nil client.ssl_cipher
      client.close
    end
  end

  # --- 6. JDBC :properties and :url SSL / PublicKeyRetrieval precedence ----

  def test_properties_ssl_mode_and_use_ssl_work_without_top_level_ssl_opts
    # properties: { sslMode: 'REQUIRED' } must NOT be clobbered by default sslMode=DISABLED
    client = Mysql2::Client.new(**CLIENT_OPTS, properties: { sslMode: 'REQUIRED' })
    refute_nil client.ssl_cipher
    client.close

    # Legacy properties: { useSSL: 'true' } must also work
    client2 = Mysql2::Client.new(**CLIENT_OPTS, properties: { useSSL: 'true' })
    refute_nil client2.ssl_cipher
    client2.close
  end

  def test_url_ssl_mode_works_and_top_level_ssl_mode_overrides
    host = CLIENT_OPTS.fetch(:host)
    port = CLIENT_OPTS.fetch(:port)
    db   = CLIENT_OPTS.fetch(:database)

    # URL with sslMode=REQUIRED enables SSL when no top-level ssl_mode is set
    client = Mysql2::Client.new(
      username: CLIENT_OPTS.fetch(:username),
      password: CLIENT_OPTS.fetch(:password),
      url: "jdbc:mysql://#{host}:#{port}/#{db}?sslMode=REQUIRED"
    )
    refute_nil client.ssl_cipher
    client.close

    # Explicit top-level ssl_mode: :disabled overrides URL's sslMode=REQUIRED
    client2 = Mysql2::Client.new(
      username: CLIENT_OPTS.fetch(:username),
      password: CLIENT_OPTS.fetch(:password),
      url: "jdbc:mysql://#{host}:#{port}/#{db}?sslMode=REQUIRED",
      ssl_mode: :disabled
    )
    assert_nil client2.ssl_cipher
    client2.close
  end

  def test_get_server_public_key_and_allow_public_key_retrieval_options
    # Create a fresh caching_sha2_password user so the server's sha2 cache is cold
    user = "mysql2_pk_test_#{Process.pid}"
    @client.query("DROP USER IF EXISTS '#{user}'@'%'")
    @client.query("CREATE USER '#{user}'@'%' IDENTIFIED WITH caching_sha2_password BY 'secret_pass_123'")
    @client.query("GRANT ALL PRIVILEGES ON `#{CLIENT_OPTS[:database]}`.* TO '#{user}'@'%'")
    @client.query("FLUSH PRIVILEGES")

    # 1. With get_server_public_key: false (and SSL disabled), cold caching_sha2_password fails
    #    with "Public Key Retrieval is not allowed"
    err = assert_raises(Mysql2::Error) do
      Mysql2::Client.new(
        **CLIENT_OPTS,
        username: user,
        password: 'secret_pass_123',
        ssl_mode: :disabled,
        get_server_public_key: false
      )
    end
    assert_match(/Public Key Retrieval is not allowed/i, err.message)

    # 2. Same when disabled via JDBC properties: { allowPublicKeyRetrieval: 'false' }
    err2 = assert_raises(Mysql2::Error) do
      Mysql2::Client.new(
        **CLIENT_OPTS,
        username: user,
        password: 'secret_pass_123',
        ssl_mode: :disabled,
        properties: { allowPublicKeyRetrieval: 'false' }
      )
    end
    assert_match(/Public Key Retrieval is not allowed/i, err2.message)

    # 3. With server_public_key_path pointing to the server's public_key.pem, it succeeds even
    #    when get_server_public_key: false!
    if @server_certs[:public_key_pem]
      Dir.mktmpdir('mysql2-pubkey') do |dir|
        pub_key_file = File.join(dir, 'public_key.pem')
        File.write(pub_key_file, @server_certs[:public_key_pem])

        # Reset user password to ensure cache remains cold
        @client.query("ALTER USER '#{user}'@'%' IDENTIFIED WITH caching_sha2_password BY 'secret_pass_456'")

        c_pub = Mysql2::Client.new(
          **CLIENT_OPTS,
          username: user,
          password: 'secret_pass_456',
          ssl_mode: :disabled,
          get_server_public_key: false,
          server_public_key_path: pub_key_file
        )
        assert_equal 1, c_pub.query('SELECT 1 AS one').first['one']
        c_pub.close
      end
    end

    # 4. With get_server_public_key: true (or by default), cold caching_sha2_password succeeds
    @client.query("ALTER USER '#{user}'@'%' IDENTIFIED WITH caching_sha2_password BY 'secret_pass_789'")
    c_ok = Mysql2::Client.new(
      **CLIENT_OPTS,
      username: user,
      password: 'secret_pass_789',
      ssl_mode: :disabled,
      get_server_public_key: true
    )
    assert_equal 1, c_ok.query('SELECT 1 AS one').first['one']
    c_ok.close
  ensure
    @client&.query("DROP USER IF EXISTS '#{user}'@'%'") rescue nil
  end

  def test_invalid_host_rejected
    assert_raises(Mysql2::Error) do
      Mysql2::Client.new(**CLIENT_OPTS, host: "127.0.0.1/test?allowLoadLocalInfile=true#")
    end
  end

  private

  def server_ssl_status(client)
    rows = client.query("SHOW STATUS WHERE Variable_name IN ('Ssl_cipher', 'Ssl_version')")
    rows.each_with_object({}) { |r, h| h[r['Variable_name']] = r['Value'] }
  end

  def fetch_server_certs
    datadir_row = @client.query("SHOW VARIABLES LIKE 'datadir'").first
    return {} unless datadir_row

    # Read ca.pem, client-cert.pem, client-key.pem, public_key.pem from server container if accessible
    certs = {}
    {
      ca_pem: 'ca.pem',
      client_cert_pem: 'client-cert.pem',
      client_key_pem: 'client-key.pem',
      public_key_pem: 'public_key.pem'
    }.each do |key, filename|
      pem = `podman exec jruby-mysql2-bench cat /var/lib/mysql/#{filename} 2>/dev/null`.delete("\0")
      certs[key] = pem if $?.success? && pem.include?('-----BEGIN ')
    end
    certs
  rescue StandardError
    {}
  end

end
