# TEMPORARY exclude -- JRuby bug, pending upstream issue + minimized reproducer.
# JRuby's Marshal.load diverges from MRI reconstructing an AR record with associations: the
# @handlers ivar reference is misread (ArgumentError: undefined class/module @handlers). JRuby
# core Marshal, not the adapter. Same category as MarshalSerializationTest / AssociationsExtensions.
exclude :test_marshalling_new_record_round_trip_with_associations, 'JRuby Marshal.load incompatibility (temporary; investigate + report upstream)'
exclude :test_marshalling_with_associations_6_1, 'JRuby Marshal.load incompatibility (temporary; investigate + report upstream)'
