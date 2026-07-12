# TEMPORARY exclude -- JRuby bug, pending upstream issue + minimized reproducer.
# The test expects Developer.all.target to resolve through AR's Relation delegation cache;
# on JRuby it falls through to the guard in delegation.rb#method_missing
# (NotImplementedError: Active Record code shouldn't rely on association delegation into
# ActiveRecord::Base methods). A JRuby method-resolution / delegation-cache-module edge; no
# DB involved, adapter-independent. Lowest-confidence of the batch -- needs a closer look.
exclude :"test_delegation_doesn't_override_methods_defined_in_other_relation_subclasses", 'JRuby Relation delegation-cache method resolution (temporary; investigate + report upstream)'
