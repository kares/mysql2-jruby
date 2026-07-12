# TEMPORARY exclude -- JRuby bug, pending upstream issue + minimized reproducer.
# Same JRuby Array#- -> QueryAttribute#hash -> serialize path as StatementCacheTest: the
# cached find_by value is a StatementCache::Substitute, and the normalize block runs against
# it (NoMethodError: undefined method 'noon' for Substitute). JRuby core Array#-, not the adapter.
exclude :test_finds_record_by_normalized_value, 'JRuby Array#- calls #hash -> serialize Substitute (temporary; investigate + report upstream)'
