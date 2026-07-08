require File.expand_path('lib/mysql2/version', __dir__)

Gem::Specification.new do |s|
  s.name     = 'mysql2'
  s.version  = Mysql2::VERSION
  s.platform = 'java'
  s.license  = 'MIT'
  s.summary  = 'A simple, fast MySQL library for Ruby -- JRuby edition (JDBC/Connector-J).'
  s.description = 'JRuby-native reimplementation of the mysql2 API, backed by ' \
                  'MySQL Connector/J over JDBC with raw text-protocol byte casting.'
  s.authors  = ['Karol Bucek']
  s.homepage = 'https://github.com/kares/jruby-mysql2'

  s.required_ruby_version = '>= 3.1'
  s.requirements << 'jar com.mysql, mysql-connector-j, >= 8.2'

  s.files = Dir[
    'lib/**/*.rb',
    'lib/mysql2.jar',
    'ext/java/**/*.java',
    'README.md',
    'Rakefile',
  ]

  s.add_dependency 'bigdecimal'
  # Runtime driver resolution is via jar-dependencies (or RUBY_MYSQL2_CONNECTOR_JAR
  # / the JVM classpath) until vendored.
end
