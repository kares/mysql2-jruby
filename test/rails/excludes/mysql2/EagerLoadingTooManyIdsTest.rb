# These load a very large IN (...) fixture; the result set is deserialized through
# JRuby's Psych, which is backed by SnakeYAML -- and SnakeYAML enforces a 3MB code-point
# limit that MRI's libyaml-backed Psych doesn't, so both raise YamlEngineException.
# JRuby YAML-engine limit, not the adapter.
exclude :test_eager_loading_too_many_ids, 'JRuby SnakeYAML document size limit exceeded'
exclude :test_preloading_too_many_ids, 'JRuby SnakeYAML document size limit exceeded'
