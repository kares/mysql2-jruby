# Thread-scheduling-sensitive pool tests: they spawn threads that hold connections and
# expect them returned within a deliberately-tiny checkout_timeout (0.2s / 0.001s). Under
# JRuby's (native) thread scheduling those threads don't yield in time, raising
# ExclusiveConnectionTimeoutError / ThreadError. The ConnectionPoolFiberTest equivalents
# (cooperative, deterministic scheduling) pass -- so this is JRuby thread behavior, not the
# adapter (the Mysql2Adapter in the message is just the connection being held).
exclude :test_connection_pool_stat, 'JRuby thread scheduling: ThreadError under tiny checkout_timeout'
exclude :test_disconnect_and_clear_reloadable_connections_attempt_to_wait_for_threads_to_return_their_conns, 'JRuby thread scheduling: connections not returned within checkout_timeout'
exclude :test_non_bang_disconnect_and_clear_reloadable_connections_throw_exception_if_threads_dont_return_their_conns, 'JRuby thread scheduling: connections not returned within checkout_timeout'

# Rails test helper sets Thread.abort_on_exception = true globally; this test only
# disables Thread.report_on_exception, not abort_on_exception. On JRuby, the timed-out
# child thread's abort_on_exception handler posts ConnectionTimeoutError asynchronously to
# Thread.main after join wakes up, escaping assert_raises.
exclude :test_checkout_queued_behind_maintenance_respects_checkout_timeout, 'JRuby Thread.abort_on_exception=true races with assert_raises around Thread#join'
