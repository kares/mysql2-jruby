# Loaded (via -r) after bundler/setup, before the Rails test files. This gem is a path
# gem in test/rails/Gemfile, so `require "mysql2"` / `gem "mysql2"` resolve via bundler
# (no Kernel#gem stub needed, which also makes it work in isolated/subprocess tests).
#
# railties isn't bundled (its irb/rdoc/rbs deps need a native build); add its lib from
# the Rails checkout so minitest 6 can autoload railties/lib/minitest/rails_plugin.rb.
if (rails = ENV["RAILS"])
  $LOAD_PATH.unshift File.join(rails, "railties", "lib")
end
