# Loaded (via -r) after bundler/setup, before the Rails test files. Puts this gem's
# lib on the load path and neutralizes the stock Mysql2Adapter's `gem "mysql2"`
# activation — our gem is required from source here, not installed into the bundle.
$LOAD_PATH.unshift File.expand_path("../../lib", __dir__)
require "mysql2"

# railties isn't bundled (its irb/rdoc/rbs deps need a native build); add its lib from
# the Rails checkout so minitest 6 can autoload railties/lib/minitest/rails_plugin.rb.
if (rails = ENV["RAILS"])
  $LOAD_PATH.unshift File.join(rails, "railties", "lib")
end

module Kernel
  alias_method :__jruby_mysql2_real_gem, :gem
  def gem(name, *reqs)
    return true if name == "mysql2"
    __jruby_mysql2_real_gem(name, *reqs)
  end
end
