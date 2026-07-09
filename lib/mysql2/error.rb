module Mysql2
  class Error < StandardError
    ENCODE_OPTS = {
      undef: :replace,
      invalid: :replace,
      replace: '?'.freeze,
    }.freeze

    ConnectionError = Class.new(Error)
    TimeoutError = Class.new(Error)

    # SQLSTATE / vendor-code mapping is coarse in this JDBC-backed prototype;
    # a few well-known connection-class codes are routed to ConnectionError.
    CODES = {
      1205 => TimeoutError,
      1044 => ConnectionError, 
      1045 => ConnectionError,
      2002 => ConnectionError, 
      2003 => ConnectionError,
      2006 => ConnectionError, 
      2013 => ConnectionError,
    }.freeze

    attr_reader :error_number, :sql_state

    # Mysql gem compatibility
    alias errno error_number
    alias error message

    def initialize(msg, server_version = nil, error_number = nil, sql_state = nil)
      @server_version = server_version
      @error_number = error_number
      @sql_state = sql_state ? sql_state.encode(Encoding::US_ASCII, **ENCODE_OPTS) : nil
      super(clean_message(msg.to_s))
    end

    def self.new_with_args(msg, server_version, error_number, sql_state)
      # SQLSTATE class "08" is the SQL-standard connection-exception class (e.g. 08S01
      # communications link failure, 08003 connection does not exist), which the JDBC
      # driver reports with a vendor code of 0; route those to ConnectionError.
      error_class = CODES[error_number]
      error_class ||= ConnectionError if sql_state.to_s.start_with?('08')
      error_class ||= self
      error_class.new(msg, server_version, error_number, sql_state)
    end

    private

    # Error text can contain user data in arbitrary/invalid encodings. Assume
    # UTF-8 and scrub to a valid string in Encoding.default_internal (or UTF-8).
    def clean_message(message)
      target = Encoding.default_internal || Encoding::UTF_8
      message = message.dup
      message.force_encoding(Encoding::UTF_8) unless message.encoding == Encoding::UTF_8
      message.encode(target, Encoding::UTF_8, **ENCODE_OPTS)
    end
  end
end
