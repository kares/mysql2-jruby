# TEMPORARY exclude -- JRuby bug, pending upstream issue + minimized reproducer.
# JRuby's Marshal.load diverges from MRI reconstructing an AR record with associations: the
# @handlers ivar reference is misread (ArgumentError: undefined class/module @handlers). JRuby
# core Marshal, not the adapter. Same category as MarshalSerializationTest / AssociationsExtensions.
exclude :test_marshalling_new_record_round_trip_with_associations, 'JRuby Marshal.load incompatibility (temporary; investigate + report upstream)'
exclude :test_marshalling_with_associations_6_1, 'JRuby Marshal.load incompatibility (temporary; investigate + report upstream)'

# ActiveSupport 8.1 pulls in prism, whose polyfill/warn checks
# Kernel.instance_method(:warn).parameters for :category. JRuby reports
# [[:rest, :messages], [:keyrest, :kw]], so prism prepends two anonymous
# Module.new wrappers onto Kernel and Object (present in Photo.ancestors).
exclude :test_no_anonymous_modules, 'prism/polyfill/warn prepends anonymous modules to Kernel and Object on JRuby'
