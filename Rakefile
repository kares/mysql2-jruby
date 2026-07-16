require 'rake/clean'

JAVA_SRC   = FileList['ext/java/mysql2/*.java']
CLASSES    = 'target/classes'
JAR        = 'lib/mysql2.jar'

CONNECTOR  = ENV['RUBY_MYSQL2_CONNECTOR_JAR'] || 'vendor/mysql-connector-j.jar'
CONNECTOR_VERSION = ENV['CONNECTOR_VERSION'] || '9.4.0'

CLEAN.include(CLASSES)
CLOBBER.include(JAR)

def jruby_jar
  File.join(RbConfig::CONFIG['libdir'], 'jruby.jar')
end

# The same JRuby that runs rake, with the connector jar on the JVM classpath
def run_jruby(script, extra_classpath: nil)
  cp = [ENV['CLASSPATH'], File.expand_path(CONNECTOR), extra_classpath].compact.join(File::PATH_SEPARATOR)
  sh({ 'CLASSPATH' => cp }, RbConfig.ruby, *Array(script))
end

desc "Download MySQL Connector/J #{CONNECTOR_VERSION} from Maven Central to #{CONNECTOR}"
task :connector do
  require 'open-uri'
  require 'digest'
  base = "https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/#{CONNECTOR_VERSION}"
  jar_url = "#{base}/mysql-connector-j-#{CONNECTOR_VERSION}.jar"

  puts "Downloading #{jar_url}"
  jar = URI.parse(jar_url).open('rb', &:read)
  expected = URI.parse("#{jar_url}.sha1").open(&:read).strip[0, 40]
  actual = Digest::SHA1.hexdigest(jar)
  abort "SHA1 mismatch: expected #{expected}, got #{actual}" unless actual == expected

  mkdir_p File.dirname(CONNECTOR)
  File.binwrite(CONNECTOR, jar)
  puts "Saved #{CONNECTOR} (#{jar.bytesize} bytes, sha1 #{actual})"
end

desc 'Compile the Java backend into lib/mysql2.jar'
task :compile do
  unless File.exist?(CONNECTOR)
    abort "Connector/J not found at #{CONNECTOR}. Run `rake connector` to download it " \
          "(or set RUBY_MYSQL2_CONNECTOR_JAR)."
  end
  mkdir_p CLASSES
  cp = [jruby_jar, CONNECTOR].join(File::PATH_SEPARATOR)
  sh "javac -cp #{cp.inspect} -d #{CLASSES} #{JAVA_SRC.join(' ')}"
  sh "jar cf #{JAR} -C #{CLASSES} ."
end

desc 'Run the mysql2 RSpec suite (spec/mysql2). Optional: rake spec[result]'
task :spec, [:file] => :compile do |_t, args|
  unless File.directory?('spec/mysql2')
    abort "spec/mysql2 not found. Copy upstream mysql2 specs there (see README) and add spec/configuration.yml."
  end
  target = args[:file] ? "spec/mysql2/#{args[:file].sub(/(_spec)?(\.rb)?$/, '')}_spec.rb" : 'spec/mysql2'
  run_jruby(['-S', 'rspec', target])
end

namespace :test do
  desc 'Create the in-house test database'
  task db: :compile do
    run_jruby(['-Ilib', '-e', <<~RUBY])
      require 'mysql2'
      c = Mysql2::Client.new(host: ENV['MYSQL_HOST'] || '127.0.0.1', port: (ENV['MYSQL_PORT'] || 13306).to_i,
                             username: ENV['MYSQL_USER'] || 'root', password: ENV['MYSQL_PASSWORD'] || 'root')
      db = ENV['MYSQL_DATABASE'] || 'test'
      c.query("CREATE DATABASE IF NOT EXISTS \#{db} DEFAULT CHARACTER SET utf8mb4")
      puts "built: " + c.query("SHOW DATABASES LIKE '\#{db}'").to_a.map(&:values).join
    RUBY
  end
end

desc 'Run the in-house minitest sanity checks (test/*.rb). TEST=file[,file] to limit.'
task test: %w[compile test:db] do
  files = ENV['TEST'] ? ENV['TEST'].split(',') : Dir['test/*_test.rb'].sort
  loader = files.map { |f| "require #{File.expand_path(f).inspect}" }.join('; ')
  # opts after `--` land in ARGV (not parsed as ruby flags), which minitest reads
  # (e.g. --verbose, --pride for color)
  opts = (ENV['TEST_OPTS'] || ENV['TESTOPTS'] || '').split
  run_jruby(['-e', loader, '--', *opts])
end

desc 'Run the performance suite on this JRuby gem (compare with `ruby benchmark/run.rb` on MRI)'
task bench: :compile do
  run_jruby('benchmark/run.rb')
end

task default: :compile

# --- mysql2 gem's own RSpec suite, run against this gem ----------------------
#
# Runs the upstream mysql2 spec suite (the ./mysql2 git submodule, or ENV MYSQL2=path)
# with our source-built gem swapped in for the C extension.
#
#   git submodule update --init mysql2        # one-time: fetch the checkout
#   rake mysql2:db:build                       # create the `test` database
#   rake mysql2:spec                           # run spec/mysql2/*_spec.rb
#   rake mysql2:spec SPEC=spec/mysql2/result_spec.rb
#
# ENV: MYSQL2 (checkout path, default ./mysql2), SPEC (comma-separated paths
# relative to the checkout, default spec/mysql2), MYSQL_HOST/PORT/USER/PASSWORD/DATABASE.
namespace :mysql2 do
  def mysql2_checkout
    dir = ENV['MYSQL2'] || File.expand_path('mysql2', __dir__) # the ./mysql2 git submodule
    unless File.directory?(File.join(dir, 'spec', 'mysql2'))
      abort "mysql2 checkout not found at #{dir} (set MYSQL2=... or run `git submodule update --init mysql2`)"
    end
    dir
  end

  desc 'Create the mysql2 spec database (default: `test` on 127.0.0.1:13306)'
  task 'db:build' => :compile do
    run_jruby(['-Ilib', '-e', <<~RUBY])
      require 'mysql2'
      c = Mysql2::Client.new(host: ENV['MYSQL_HOST'] || '127.0.0.1', port: (ENV['MYSQL_PORT'] || 13306).to_i,
                             username: 'root', password: ENV['MYSQL_PASSWORD'] || 'root')
      db = ENV['MYSQL_DATABASE'] || 'test'
      c.query("CREATE DATABASE IF NOT EXISTS \#{db} DEFAULT CHARACTER SET utf8mb4")
      puts "built: " + c.query("SHOW DATABASES LIKE '\#{db}'").to_a.map(&:values).join
    RUBY
  end

  desc "Run the mysql2 gem's RSpec suite against this gem (MYSQL2=../mysql2). SPEC=file to limit; NO_EXCLUDES=true to run all."
  task :spec => :compile do
    dir = mysql2_checkout
    harness = File.expand_path('test/mysql2/harness.rb', __dir__)
    excludes = File.expand_path('test/mysql2/rspec_excludes.rb', __dir__)

    # Seed the suite's git-ignored local config if the checkout doesn't have one,
    # rendering the template against MYSQL_HOST/PORT/USER/PASSWORD/DATABASE.
    config_dst = File.join(dir, 'spec', 'configuration.yml')
    unless File.exist?(config_dst)
      require 'erb'
      template = File.read(File.expand_path('test/mysql2/configuration.yml', __dir__))
      File.write(config_dst, ERB.new(template).result)
      puts "seeded #{config_dst}"
    end

    specs = ENV['SPEC'] ? ENV['SPEC'].split(',') : ['spec/mysql2']
    cp = [ENV['CLASSPATH'], File.expand_path(CONNECTOR)].compact.join(File::PATH_SEPARATOR)
    # -r our harness so our mysql2 loads before spec_helper; -r the excludes hook
    # (skips known out-of-scope examples from test/mysql2/excludes.txt) unless disabled.
    requires = ['-r', harness]
    requires += ['-r', excludes] unless ENV['NO_EXCLUDES'] == 'true'
    # Run from the checkout so spec_helper's relative paths (spec/configuration.yml,
    # spec/test_data) resolve.
    Dir.chdir(dir) do
      sh({ 'CLASSPATH' => cp }, RbConfig.ruby, '-S', 'rspec', '-I', 'spec', *requires, *specs) do |ok, res|
        exit(res.exitstatus || 1) unless ok # spec failures: exit with rspec's status, no rake backtrace
      end
    end
  end
end

# --- Rails' own ActiveRecord test suite, run against this gem ----------------
#
# Reuses Rails' stock Mysql2Adapter driving our Mysql2::Client. Modeled on
# activerecord-jdbc-adapter's rakelib/rails.rake.
#
#   rake rails:bundle                         # one-time: install test deps (network)
#   rake rails:db:build                       # create activerecord_unittest{,2}
#   rake rails:test                           # full suite (adapters/* limited to mysql2)
#   rake 'rails:test' TEST=test/cases/transactions_test.rb
#   AR_PREPARED_STATEMENTS=true rake rails:test TEST=...
#
# Point RAILS at a Rails checkout (defaults to ./rails); on CI each matrix job
# checks out a different Rails version there.
#
# ENV: RAILS (checkout path, default ./rails), TEST (comma-separated files),
# TESTOPTS, MYSQL_HOST/PORT/USER/PASSWORD, N (minitest workers, default 0 — parallel
# crashes on JRuby), NO_EXCLUDES.
namespace :rails do
  def rails_ar_path
    rails = ENV['RAILS'] || File.expand_path('rails', __dir__) # ./rails checkout (submodule or CI clone)
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
    sh(env, "#{RbConfig.ruby} #{ruby_opts} #{loader} #{files_str} #{testopts}") do |ok, res|
      exit(res.exitstatus || 1) unless ok # test failures: exit with the runner's status, no rake backtrace
    end
  end

  namespace :db do
    desc 'Create the activerecord_unittest{,2} databases + user for the Rails suite'
    task :build => :compile do
      run_jruby(['-Ilib', '-e', <<~RUBY])
        require 'mysql2'
        c = Mysql2::Client.new(host: ENV['MYSQL_HOST'] || '127.0.0.1', port: (ENV['MYSQL_PORT'] || 13306).to_i,
                               username: 'root', password: ENV['MYSQL_PASSWORD'] || 'root')
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
