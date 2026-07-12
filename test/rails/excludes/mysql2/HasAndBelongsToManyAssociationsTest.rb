# TEMPORARY exclude -- JRuby bug, pending upstream issue + minimized reproducer.
# Marshaling a HABTM association; JRuby's Marshal.load can't resolve the join-model
# reference (ArgumentError: undefined class/module categories_posts). JRuby core Marshal,
# not the adapter.
exclude :test_marshal_dump, 'JRuby Marshal incompatibility (temporary; investigate + report upstream)'
