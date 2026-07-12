# These multi-database tests hardcode `adapter: "sqlite3"` in their connection configs
# (secondary/shard databases), so they try to load the C-ext sqlite3 gem regardless of
# ARCONN=mysql2 -- which can't run on JRuby. (arjdbc excludes the same set for mysql2.)
exclude :test_roles_can_be_swapped_granularly, 'tries to load the sqlite3 driver'
exclude :test_shards_can_be_swapped_granularly, 'tries to load the sqlite3 driver'
exclude :test_roles_and_shards_can_be_swapped_granularly, 'tries to load the sqlite3 driver'
exclude :test_connected_to_many, 'tries to load the sqlite3 driver'
exclude :test_prevent_writes_can_be_changed_granularly, 'tries to load the sqlite3 driver'
exclude :test_shard_swapping_prohibition_exception_recovery, 'tries to load the sqlite3 driver (Rails 8, not in arjdbc)'
