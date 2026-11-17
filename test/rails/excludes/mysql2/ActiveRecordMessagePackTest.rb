# ActiveSupport::MessagePack registers a custom extension type to (de)serialize AR records,
# but the JRuby msgpack gem (msgpack-*-java) doesn't honor that extension registration the
# way the C msgpack does, so packing a record raises UnserializableObjectError. JRuby
# msgpack-java limitation, not the adapter. (Rails 8.0.5 method names use literal :: and ?,
# unlike arjdbc's underscore versions.)
exclude :test_roundtrips_binary_attribute, 'JRuby msgpack-java does not support the ActiveSupport::MessagePack AR extension'
exclude :test_roundtrips_record_and_cached_associations, 'JRuby msgpack-java does not support the ActiveSupport::MessagePack AR extension'
exclude :"test_roundtrips_new_record?_status", 'JRuby msgpack-java does not support the ActiveSupport::MessagePack AR extension'
exclude :"test_raises_ActiveSupport::MessagePack::MissingClassError_if_record_class_no_longer_exists", 'JRuby msgpack-java does not support the ActiveSupport::MessagePack AR extension'
exclude :test_roundtrips_time_attribute, 'JRuby msgpack-java does not support the ActiveSupport::MessagePack AR extension'
