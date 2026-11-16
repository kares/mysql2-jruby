# Rails 8.1 asserts Ractor.shareable? on cast types without guarding defined?(Ractor);
# JRuby does not implement MRI Ractor.
exclude :test_type_map_is_ractor_shareable, 'JRuby does not support Ractor'
