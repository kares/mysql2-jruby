# Runs a benchmark script under both CRuby (mysql2 C extension) and JRuby (this
# gem) via `mise exec`, then prints them side by side.
#
#   ruby benchmark/compare.rb            # benchmark/types.rb, the per-type suite
#   ruby benchmark/compare.rb run.rb     # the mixed-scenario suite
#
# Both Rubies must be installed under mise, and the CRuby one needs the mysql2
# gem (`mise exec ruby@3.4.8 -- gem install mysql2`). ENV:
#   BENCH_MRI=3.4.8              mise ruby for the C-extension side
#   BENCH_JRUBY=jruby-10.0.6.0   mise ruby for this gem (default: ./mise.toml)
# plus everything the benchmark script itself understands (BENCH_TIME, ...).
require 'json'

ROOT = File.expand_path('..', __dir__)

script = ARGV[0] || 'types.rb'
script = File.join('benchmark', script) unless script.include?('/')
abort "no such script: #{script}" unless File.exist?(File.join(ROOT, script))

def mise_toml_ruby
  toml = File.join(ROOT, 'mise.toml')
  File.exist?(toml) ? File.read(toml)[/^\s*ruby\s*=\s*"([^"]+)"/, 1] : nil
end

MRI   = ENV['BENCH_MRI']   || '3.4.8'
JRUBY = ENV['BENCH_JRUBY'] || mise_toml_ruby || 'jruby'

unless File.exist?(File.join(ROOT, 'lib', 'mysql2.jar'))
  abort 'lib/mysql2.jar is missing -- run `rake compile` first'
end

# Pin both engines to the same cores. On a hybrid CPU (P/E, Zen5/Zen5c) an
# unpinned run compares whatever cores the scheduler happened to pick, and the
# max clock between core types can differ by ~50%.
CPUS = ENV['BENCH_CPUS']

def run(ruby, script, out, env)
  cmd = ['mise', 'exec', "ruby@#{ruby}", '--', 'ruby', script]
  cmd = ['taskset', '-c', CPUS] + cmd if CPUS
  warn "\n$ #{cmd.join(' ')}"
  env = { 'BENCH_FORMAT' => 'json', 'BENCH_OUT' => out }.merge(env)
  system(env, *cmd, chdir: ROOT) || abort("failed: #{cmd.join(' ')}")
  JSON.parse(File.read(out))
end

out_dir = ENV['TMPDIR'] || '/tmp'
# the first run seeds the table; the second reuses it
mri = run(MRI, script, File.join(out_dir, 'bench-mri.json'), {})
jruby = run(JRUBY, script, File.join(out_dir, 'bench-jruby.json'),
            'BENCH_SETUP' => '0',
            'CLASSPATH' => File.join(ROOT, 'vendor', 'mysql-connector-j.jar'))

by_label = jruby['results'].to_h { |r| [r['label'], r] }

puts
puts mri['subtitle'] if mri['subtitle']
printf "\n%-18s %22s %22s %10s\n", '', mri['engine'], jruby['engine'], 'JRuby vs'
printf "%-18s %12s %9s %12s %9s %10s  %s\n",
       'scenario', 'ops/sec', 'ns/value', 'ops/sec', 'ns/value', 'CRuby', 'decodes to'
puts '-' * 96

ratios = []
mismatched = []
mri['results'].each do |a|
  b = by_label[a['label']]
  next unless b

  ratio = b['ops'] / a['ops']
  ratios << ratio
  same = a['note'] == b['note']
  mismatched << a['label'] unless same || a['note'].nil?
  decoded = a['note'] ? (same ? a['note'] : "#{a['note']} vs #{b['note']}  <-- differs") : ''
  ns = ->(r) { r['ns_per_value'] ? format('%9.1f', r['ns_per_value']) : ' ' * 9 }
  printf "%-18s %12.1f %s %12.1f %s %9.2fx  %s\n",
         a['label'], a['ops'], ns[a], b['ops'], ns[b], ratio, decoded
end

puts '-' * 96
geomean = Math.exp(ratios.sum { |r| Math.log(r) } / ratios.size)
printf "%-18s %65.2fx\n", 'geometric mean', geomean
puts
puts '> 1.00x means JRuby + this gem is faster than CRuby + mysql2.'
unless mismatched.empty?
  puts "! not comparable (different Ruby class per engine): #{mismatched.join(', ')}"
end
