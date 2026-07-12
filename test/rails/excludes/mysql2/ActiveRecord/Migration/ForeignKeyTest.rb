# Uses a config with adapter: "sqlite3" to exercise the foreign-key bypass setting,
# so it tries to load the C-ext sqlite3 gem (can't run on JRuby).
exclude :test_does_not_create_foreign_keys_when_bypassed_by_config, 'tries to load the sqlite3 driver'
