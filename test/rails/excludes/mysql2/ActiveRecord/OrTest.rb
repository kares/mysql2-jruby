# See FinderTest.rb: querying with an out-of-range integer (2**63) raises
# ActiveModel::RangeError on JRuby because Array#- (WhereClause#or's predicate diff) calls
# #hash, and QueryAttribute#hash eagerly serializes the value. JRuby core-lib quirk,
# independent of the adapter.
exclude :test_or_with_large_number, 'JRuby Array#- calls #hash -> out-of-range integer serialize -> RangeError'
