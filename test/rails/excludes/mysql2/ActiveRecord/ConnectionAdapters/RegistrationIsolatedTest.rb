# ActiveSupport::Testing::Isolation runs each test in a subprocess; JRuby can't fork, so it
# re-execs without our `-r mysql2/compat` harness and crashes ("mysql2 is not part of the
# bundle"). The test was renamed between Rails versions, so exclude both names.
exclude :"test_#resolve_raises_if_the_adapter_is_using_the_pre_7.2_adapter_registration_API", 'Isolation subprocess crashes on JRuby (no fork)' # 8.0
exclude :"test_#resolve_raises_if_the_adapter_maybe_is_using_the_pre_7.2_adapter_registration_API_but_we_are_not_sure", 'Isolation subprocess crashes on JRuby (no fork)' # 7.2
