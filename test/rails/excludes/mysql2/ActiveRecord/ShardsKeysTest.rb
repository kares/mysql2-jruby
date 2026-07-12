# All of ShardsKeysTest configures sharded databases with adapter: "sqlite3", so every
# test tries to load the C-ext sqlite3 gem (can't run on JRuby) regardless of ARCONN.
# Not in arjdbc's excludes (Rails 8 / verified against the current source).
exclude :test_connected_to_all_shards, 'tries to load the sqlite3 driver'
exclude :test_connected_to_all_shards_can_switch_each_to_reading_role, 'tries to load the sqlite3 driver'
exclude :test_connected_to_all_shards_respects_preventing_writes, 'tries to load the sqlite3 driver'
exclude :test_connects_to_sets_shard_keys, 'tries to load the sqlite3 driver'
exclude :test_connects_to_sets_shard_keys_for_descendents, 'tries to load the sqlite3 driver'
exclude :"test_sharded?", 'tries to load the sqlite3 driver'
