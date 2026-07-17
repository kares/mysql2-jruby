# On Rails 7.2 this drives error translation (with Minitest::Mock binds + a fake DB error)
# into unbounded recursion -> Java StackOverflowError, instead of raising StatementInvalid.
# Rails 8.0's path handles it, so the exclude is scoped to < 8.
exclude :test_statement_and_binds_are_set_on_select, 'recurses to Java StackOverflowError on Rails 7.2' if ActiveRecord::VERSION::MAJOR < 8
