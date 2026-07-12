# Querying with an out-of-range integer (> bigint max, 2**63) raises ActiveModel::RangeError
# on JRuby but not MRI: JRuby's Array#- (used to diff WHERE predicates) calls #hash, and
# QueryAttribute#hash eagerly serializes the value; MRI's Array#- compares small arrays via
# ==/eql?, which short-circuits on the raw value before serializing. JRuby core-lib quirk,
# independent of the adapter.
exclude :test_exists_with_large_number, 'JRuby Array#- calls #hash -> out-of-range integer serialize -> RangeError'
exclude :test_find_by_bang_on_relation_with_large_number, 'JRuby Array#- calls #hash -> out-of-range integer serialize -> RangeError'
