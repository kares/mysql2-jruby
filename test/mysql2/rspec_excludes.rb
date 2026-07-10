# External RSpec excludes — the rspec analog of minitest-excludes. Skips known
# out-of-scope / expected-failing examples WITHOUT editing the upstream mysql2 spec
# files. Loaded via `rspec -r` (see the mysql2:spec rake task); reads patterns from
# an excludes file that lives here, in this gem's repo — not in the mysql2 checkout.
#
# File: ENV['RSPEC_EXCLUDES'] or excludes.txt beside this file. One pattern per line.
# Comment lines start with '# ' (hash + space), '##', or a bare '#'; blank lines are
# ignored. A leading '#' followed by a word char is a PATTERN, not a comment, because
# mysql2 example descriptions contain method names like '#automatic_close'. A pattern
# matches an example's full description as a substring, or as a regexp when /.../.
module Mysql2SpecExcludes
  FILE = ENV["RSPEC_EXCLUDES"] || File.expand_path("excludes.txt", __dir__)
  COMMENT = /\A#(?:\s|#|\z)/ # '# foo', '##', or bare '#' — but not '#method_name'

  def self.matchers
    @matchers ||= (File.exist?(FILE) ? File.readlines(FILE) : []).filter_map do |raw|
      line = raw.strip
      next if line.empty? || COMMENT.match?(line)
      line =~ %r{\A/(.*)/\z} ? Regexp.new(Regexp.last_match(1)) : line
    end
  end

  def self.excluded?(description)
    matchers.any? { |m| m.is_a?(Regexp) ? m.match?(description) : description.include?(m) }
  end
end

RSpec.configure do |config|
  config.before(:each) do |example|
    if Mysql2SpecExcludes.excluded?(example.full_description)
      skip "excluded via #{File.basename(Mysql2SpecExcludes::FILE)}"
    end
  end
end
