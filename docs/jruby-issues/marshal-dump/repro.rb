# Reproducer: Marshal.dump produces a corrupt stream on JRuby for a cyclic ActiveRecord
# object graph (a new record with an in-memory-built association). MRI round-trips it fine.
#
#   gem install activerecord sqlite3   # (any adapter; sqlite3 needs no server)
#   ruby jruby-marshal-repro.rb
require "active_record"

ActiveRecord::Base.establish_connection(adapter: "sqlite3", database: ":memory:")
c = ActiveRecord::Base.connection
c.create_table(:posts,    force: true) { |t| t.string :title }
c.create_table(:comments, force: true) { |t| t.integer :post_id; t.string :body }

class Post    < ActiveRecord::Base; has_many :comments;    end
class Comment < ActiveRecord::Base; belongs_to :post;      end

puts "#{RUBY_ENGINE} #{RUBY_ENGINE_VERSION} (#{RUBY_VERSION}), AR #{ActiveRecord::VERSION::STRING}"

post = Post.new
puts "plain new record:            #{(Marshal.load(Marshal.dump(post)); 'OK') rescue $!.inspect}"

post.comments.build            # populate association cache -> cyclic graph
puts "new record + built assoc:    #{(Marshal.load(Marshal.dump(post)); 'OK') rescue $!.inspect}"
#   MRI    => OK
#   JRuby  => ArgumentError: undefined class/module @handlers
