# TEMPORARY exclude -- JRuby bug, pending upstream issue + minimized reproducer.
# Marshaling a relation carrying anonymous extension modules; JRuby's Marshal can't
# resolve the @table reference (ArgumentError: undefined class/module @table). JRuby
# core Marshal, not the adapter.
exclude :test_marshalling_extensions, 'JRuby Marshal incompatibility (temporary; investigate + report upstream)'
exclude :test_marshalling_named_extensions, 'JRuby Marshal incompatibility (temporary; investigate + report upstream)'
