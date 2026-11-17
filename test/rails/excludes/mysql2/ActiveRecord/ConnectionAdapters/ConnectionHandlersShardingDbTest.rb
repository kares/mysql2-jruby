#
# These tests all try various multi-DB things, using an SQLite3 database. When
# running the Rails tests from AR-JDBC, the SQLite3 JARs are not on the search
# path so this fails.
#
exclude :test_establish_connection_using_3_levels_config, 'tries to load SQLite3 driver'
exclude :test_establish_connection_using_3_levels_config_with_shards_and_replica, 'tries to load SQLite3 driver'
exclude :test_establishing_a_connection_in_connected_to_block_uses_current_role_and_shard, 'tries to load SQLite3 driver'
exclude :test_retrieves_proper_connection_with_nested_connected_to, 'tries to load SQLite3 driver'
exclude :test_same_shards_across_clusters, 'tries to load SQLite3 driver'
exclude :test_sharding_separation, 'tries to load SQLite3 driver'
exclude :test_swapping_granular_shards_and_roles_in_a_multi_threaded_environment, 'tries to load SQLite3 driver'
exclude :test_swapping_shards_and_roles_in_a_multi_threaded_environment, 'tries to load SQLite3 driver'
exclude :test_swapping_shards_globally_in_a_multi_threaded_environment, 'tries to load SQLite3 driver'
exclude :test_switching_connections_via_handler, 'tries to load SQLite3 driver'
exclude :test_can_swap_roles_while_shard_swapping_is_prohibited, 'tries to load the sqlite3 driver (Rails 8, not in arjdbc)'
exclude :test_cannot_swap_shards_while_prohibited, 'tries to load the sqlite3 driver (Rails 8, not in arjdbc)'
exclude :test_default_shard_is_chosen_by_first_key_or_default, 'tries to load the sqlite3 driver (Rails 8, not in arjdbc)'
exclude :test_establish_connection_using_3_levels_config_with_integer_keys, 'tries to load SQLite3 driver'
