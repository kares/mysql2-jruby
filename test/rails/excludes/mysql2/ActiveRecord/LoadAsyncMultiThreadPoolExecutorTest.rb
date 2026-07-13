# TEMPORARY exclude -- JRuby thread-scheduling flake, not the adapter.
# Asserts the query ran on a background thread (status[:async] == supports_concurrent_connections?).
# It relies on wait_for_async_query draining the shared Concurrent::ThreadPoolExecutor before the
# main thread touches the result; on JRuby (no GIL) that scheduled/completed-task-count race isn't
# tight under full-suite load, so the main thread sometimes runs the query synchronously
# (status[:async] == false). Passes in isolation and even with the whole load_async_test.rb file;
# only fails under full-suite thread/GC pressure. Same family as ConnectionPoolThreadTest.
exclude :test_simple_query, 'JRuby thread scheduling: async executor drain race under full-suite load (temporary)'
