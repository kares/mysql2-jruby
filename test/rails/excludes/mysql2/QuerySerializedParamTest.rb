# TEMPORARY exclude -- JRuby bug, pending upstream issue + minimized reproducer.
# Same JRuby Array#- -> QueryAttribute#hash -> serialize path as StatementCacheTest: the
# cached find_by value is a StatementCache::Substitute and the serialized column tries to
# YAML-dump it (Psych::DisallowedClass: ...Substitute). JRuby core Array#-, not the adapter.
exclude :test_query_serialized_string, 'JRuby Array#- calls #hash -> serialize Substitute (temporary; investigate + report upstream)'
