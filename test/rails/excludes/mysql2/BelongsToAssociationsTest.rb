# Same JRuby Array#- / QueryAttribute#hash out-of-range-integer issue as FinderTest /
# OrTest: assigning an id past bigint max (2**63) raises ActiveModel::RangeError on JRuby
# but not MRI. Adapter-independent (see FinderTest.rb).
exclude :test_belongs_to_with_out_of_range_value_assigning, 'JRuby Array#- calls #hash -> out-of-range integer serialize -> RangeError'
