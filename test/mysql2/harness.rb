# Loaded via `rspec -r` so this gem (built from source) is required before the mysql2
# spec suite's spec_helper does `require 'mysql2'` — which then no-ops, instead of the
# checkout's own lib/mysql2.rb (the C-ext loader, which can't run on JRuby).
$LOAD_PATH.unshift File.expand_path("../../lib", __dir__)
require "mysql2"
