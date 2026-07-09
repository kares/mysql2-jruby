require 'rake/clean'

JAVA_SRC   = FileList['ext/java/mysql2/*.java']
CLASSES    = 'target/classes'
JAR        = 'lib/mysql2.jar'

# Connector/J is needed on the compile classpath (java.sql only at runtime is
# not enough because Field.fromMeta etc. reference JDBC types via java.sql).
CONNECTOR  = ENV['RUBY_MYSQL2_CONNECTOR_JAR'] || 'prototype/lib/mysql-connector-j.jar'

CLEAN.include(CLASSES)
CLOBBER.include(JAR)

def jruby_jar
  # jruby.jar from the running JRuby (works under `jruby -S rake`).
  File.join(RbConfig::CONFIG['libdir'], 'jruby.jar')
end

desc 'Compile the Java backend into lib/mysql2.jar'
task :compile do
  mkdir_p CLASSES
  cp = [jruby_jar, CONNECTOR].join(File::PATH_SEPARATOR)
  sh "javac -cp #{cp.inspect} -d #{CLASSES} #{JAVA_SRC.join(' ')}"
  sh "jar cf #{JAR} -C #{CLASSES} ."
end

# The same JRuby that runs rake (not a mise-default CRuby), with the connector
# jar on the JVM classpath so lib/mysql2.rb can load the driver.
def run_jruby(script, extra_classpath: nil)
  cp = [ENV['CLASSPATH'], File.expand_path(CONNECTOR), extra_classpath].compact.join(File::PATH_SEPARATOR)
  sh({ 'CLASSPATH' => cp }, RbConfig.ruby, *Array(script))
end

desc 'Run the mysql2 RSpec suite (spec/mysql2). Optional: rake spec[result]'
task :spec, [:file] => :compile do |_t, args|
  unless File.directory?('spec/mysql2')
    abort "spec/mysql2 not found. Copy upstream mysql2 specs there (see README) and add spec/configuration.yml."
  end
  target = args[:file] ? "spec/mysql2/#{args[:file].sub(/(_spec)?(\.rb)?$/, '')}_spec.rb" : 'spec/mysql2'
  run_jruby(['-S', 'rspec', target])
end

desc 'Run the sanity scripts in test/ (needs MySQL on 127.0.0.1:13306, db bench)'
task test: :compile do
  Dir['test/*.rb'].sort.each { |f| run_jruby(f) }
end

desc 'Run the smoke test (needs a MySQL on 127.0.0.1:13306, db bench)'
task smoke: :compile do
  run_jruby('test/smoke.rb')
end

task default: :compile

# --- Rails' own ActiveRecord test suite, run against this gem ----------------
#
# Reuses Rails' stock Mysql2Adapter driving our Mysql2::Client. Modeled on
# activerecord-jdbc-adapter's rakelib/rails.rake.
#
#   RAILS=../rails rake rails:bundle          # one-time: install test deps (network)
#   RAILS=../rails rake rails:db:build        # create activerecord_unittest{,2}
#   RAILS=../rails rake rails:test            # full suite (adapters/* limited to mysql2)
#   RAILS=../rails rake 'rails:test' TEST=test/cases/transactions_test.rb
#   AR_PREPARED_STATEMENTS=true RAILS=../rails rake rails:test TEST=...
#
# ENV: RAILS (checkout path, default ../rails), TEST (comma-separated files),
# TESTOPTS, MYSQL_HOST/PORT/USER/PASSWORD, N (minitest workers, default 0 — parallel
# crashes on JRuby), NO_EXCLUDES.
namespace :rails do
  def rails_ar_path
    rails = ENV['RAILS'] || File.expand_path('../rails', __dir__)
    ar = File.join(rails, 'activerecord')
    abort "Rails checkout not found (set RAILS=...); tried #{rails}" unless File.directory?(File.join(ar, 'test'))
    [rails, ar]
  end

  def rails_env(rails)
    {
      'RAILS'          => rails,
      'BUNDLE_GEMFILE' => ENV['BUNDLE_GEMFILE'] || File.expand_path('test/rails/Gemfile', __dir__),
      'ARCONN'         => 'mysql2',
      'ARCONFIG'       => ENV['ARCONFIG'] || File.expand_path('test/rails/config.yml', __dir__),
      'CLASSPATH'      => [ENV['CLASSPATH'], File.expand_path(CONNECTOR)].compact.join(File::PATH_SEPARATOR),
      'N'              => ENV['N'] || '0',
    }
  end

  desc 'Install the Rails test-suite dependencies (test/rails/Gemfile). Needs network once.'
  task :bundle do
    rails, = rails_ar_path
    sh(rails_env(rails).slice('RAILS', 'BUNDLE_GEMFILE'), "#{RbConfig.ruby} -S bundle install")
  end

  desc 'Run Rails ActiveRecord tests against this gem. ENV TEST=file to limit; see comments.'
  task :test => :compile do
    require 'rake/testtask'
    rails, ar_path = rails_ar_path
    harness = File.expand_path('test/rails/harness.rb', __dir__)

    files =
      if ENV['TEST']
        ENV['TEST'].split(',').map { |f| File.expand_path(f, ar_path) }
      else
        # Whole suite, but limit adapters/* to mysql2 (as Rails' own Rakefile does).
        Dir.chdir(ar_path) do
          Dir.glob('test/cases/**/*_test.rb').reject { |x| x =~ %r{/adapters/} || x =~ %r{/encryption/} } +
            Dir.glob('test/cases/adapters/mysql2/**/*_test.rb')
        end.map { |f| File.expand_path(f, ar_path) }
      end

    ruby_opts = %(-I"#{File.join(ar_path, 'test')}" -C "#{ar_path}" -rbundler/setup -r"#{harness}")
    ruby_opts += ' -rminitest -rminitest/excludes' unless ENV['NO_EXCLUDES'] == 'true'

    loader = Class.new(Rake::TestTask) { def define; end }.new.run_code # rake test-loader
    files_str = files.map { |f| %("#{f}") }.join(' ')
    testopts = ENV['TESTOPTS'] || '--verbose'

    env = rails_env(rails)
    env['EXCLUDE_DIR'] ||= File.expand_path('test/rails/excludes/mysql2', __dir__)
    sh(env, "#{RbConfig.ruby} #{ruby_opts} #{loader} #{files_str} #{testopts}")
  end

  namespace :db do
    desc 'Create the activerecord_unittest{,2} databases + user for the Rails suite'
    task :build => :compile do
      run_jruby(['-Ilib', '-e', <<~RUBY])
        require 'mysql2'
        c = Mysql2::Client.new(host: ENV['MYSQL_HOST'] || '127.0.0.1', port: (ENV['MYSQL_PORT'] || 13306).to_i,
                               username: 'root', password: ENV['MYSQL_ROOT_PASSWORD'] || 'root')
        %w[activerecord_unittest activerecord_unittest2].each do |db|
          c.query("CREATE DATABASE IF NOT EXISTS \#{db} DEFAULT CHARACTER SET utf8mb4")
        end
        user = ENV['MYSQL_USER'] || 'rails'
        c.query("CREATE USER IF NOT EXISTS '\#{user}'@'%'")
        c.query("GRANT ALL PRIVILEGES ON *.* TO '\#{user}'@'%'")
        c.query('FLUSH PRIVILEGES')
        puts "built: " + c.query("SHOW DATABASES LIKE 'activerecord%'").to_a.map(&:values).join(', ')
      RUBY
    end
  end
end
