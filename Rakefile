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
