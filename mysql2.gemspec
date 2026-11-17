require File.expand_path('lib/mysql2/version', __dir__)

Gem::Specification.new do |s|
  s.name     = 'mysql2-jruby'
  s.version  = Mysql2::VERSION
  s.platform = 'java'
  s.license  = 'LGPL-3.0-or-later'
  s.summary  = 'A simple, fast MySQL library for JRuby'
  s.description = "JRuby native re-implementation of the Mysql2 gem, " +
                  "with emphasis on being able to run ActiveRecord, " +
                  'requires MySQL Connector/J JDBC driver.'
  s.authors  = ['Karol Bucek']
  s.homepage = 'https://github.com/kares/mysql2-jruby'

  s.required_ruby_version = '>= 3.1' # >= JRuby 9.4
  s.requirements << "jar com.mysql:mysql-connector-j, >= #{Mysql2::MINIMAL_CONNECTOR_VERSION}"

  s.files = Dir.chdir(__dir__) do
    files = `git ls-files -z ext lib README.md CHANGELOG.md LICENSE COPYING mysql2.gemspec`.split("\x0")
    files << 'lib/mysql2.jar'
    files
  end

  # bigdecimal and date are no longer default gems on Ruby 3.4+; lib/mysql2.rb requires both
  s.add_dependency 'bigdecimal', '< 5.0'
  s.add_dependency 'date', '< 4.1'
end
