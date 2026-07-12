# TEMPORARY exclude -- JRuby bug, pending upstream issue + minimized reproducer.
# JRuby's Marshal.load diverges from MRI reconstructing an AR object from a stored
# Rails-6.1-format blob: an @attribute ivar reference is misread as a constant
# (NameError: uninitialized constant @attribute). JRuby core Marshal, not the adapter.
exclude :test_rails_6_1_rountrip, 'JRuby Marshal.load incompatibility (temporary; investigate + report upstream)'
