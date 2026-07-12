exclude :test_connection_pool_starts_reaper, 'hangs on JRuby: reaper thread / high-frequency timer'
exclude :test_some_time, 'hangs on JRuby: reaper thread / high-frequency timer'
exclude :test_reaper_works_after_pool_discard, 'ABBA deadlock: pool.discard! holds pool mutex then Reaper mutex, reaper holds Reaper mutex then pool mutex'
