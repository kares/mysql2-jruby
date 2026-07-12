# Thread-scheduling-sensitive pool tests: they spawn threads that hold connections and
# expect them returned within a deliberately-tiny checkout_timeout (0.2s / 0.001s). Under
# JRuby's (native) thread scheduling those threads don't yield in time, raising
# ExclusiveConnectionTimeoutError / ThreadError. The ConnectionPoolFiberTest equivalents
# (cooperative, deterministic scheduling) pass -- so this is JRuby thread behavior, not the
# adapter (the Mysql2Adapter in the message is just the connection being held).
exclude :test_connection_pool_stat, 'JRuby thread scheduling: ThreadError under tiny checkout_timeout'
exclude :test_disconnect_and_clear_reloadable_connections_attempt_to_wait_for_threads_to_return_their_conns, 'JRuby thread scheduling: connections not returned within checkout_timeout'
exclude :test_non_bang_disconnect_and_clear_reloadable_connections_throw_exception_if_threads_dont_return_their_conns, 'JRuby thread scheduling: connections not returned within checkout_timeout'
