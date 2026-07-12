# TEMPORARY exclude -- JRuby bug, pending upstream issue + minimized reproducer.
# AR's marshal_load does instance_variable_set with a name JRuby rejects
# (NameError: 'Hash' is not allowable as an instance variable name). MRI accepts the
# Marshal-reconstructed name; JRuby enforces ivar naming. JRuby core Marshal, not the adapter.
exclude :test_marshal_dump, 'JRuby Marshal incompatibility (temporary; investigate + report upstream)'
