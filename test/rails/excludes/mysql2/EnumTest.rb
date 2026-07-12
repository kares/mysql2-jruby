# These assert an enum-clash warning is captured by ActiveSupport's MockLogger, which
# relies on Kernel#warn being PRIVATE so its method_missing records the message. On JRuby,
# prism's warn polyfill (prism is a minitest-6 dependency; prism/polyfill/warn.rb activates
# because JRuby's native Kernel#warn doesn't expose the `category:` parameter) prepends a
# PUBLIC #warn to Object. So logger.warn(msg) dispatches to that real (printing) warn instead
# of method_missing -- the message goes to stderr and logged(:warn) stays empty. The enum
# warning itself fires correctly; only the MockLogger capture is defeated. Not the adapter.
exclude :"test_enum_logs_a_warning_if_auto-generated_negative_scopes_would_clash_with_other_enum_names", 'JRuby: prism warn polyfill makes Object#warn public, defeating MockLogger method_missing capture'
exclude :"test_enum_logs_a_warning_if_auto-generated_negative_scopes_would_clash_with_other_enum_names_regardless_of_order", 'JRuby: prism warn polyfill makes Object#warn public, defeating MockLogger method_missing capture'
