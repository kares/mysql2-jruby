# Loaded (via -r) after bundler/setup, before the Rails test files
#
# mysql2/compat registers a `mysql2` alias in Bundler so the mysql2 adapter's
#   `gem "mysql2", "~> 0.5"` resolves to this gem (bundled under its own name)
require "mysql2/compat"

# railties isn't bundled (its irb/rdoc/rbs deps need a native build)
# add its lib from so minitest can autoload railties/lib/minitest/rails_plugin.rb
if (rails = ENV["RAILS"])
  $LOAD_PATH.unshift File.join(rails, "railties", "lib")
end
