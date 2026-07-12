# TEMPORARY exclude -- JRuby bug, pending upstream issue + minimized reproducer.
# JRuby's Array#- (in WhereClause#non_empty_predicates) computes element #hash, and
# QueryAttribute#hash eagerly serializes value_for_database; MRI's Array#- compares small
# arrays with ==/eql? and never serializes. On the cached find_by path the value is a
# StatementCache::Substitute placeholder, so serialize blows up by column type -- here
# Float type calls Substitute.to_f (NoMethodError). JRuby core Array#-, not the adapter.
# Same root cause as FinderTest / BelongsToAssociationsTest / OrTest.
exclude :test_statement_cache_with_strictly_cast_attribute, 'JRuby Array#- calls #hash -> serialize Substitute (temporary; investigate + report upstream)'
