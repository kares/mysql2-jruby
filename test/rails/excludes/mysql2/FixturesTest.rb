# the bulk-insert / max_allowed_packet cases used to be excluded as "not yet implemented";
# they pass since the multi-statement (set_server_option + abandon_results!) path landed

exclude :test_fixtures_are_set_up_with_database_env_variable, 'tries to load SQLite3 driver'
