# A concurrency "duel" asserting thread A's transaction out-lasts B's (first.end > second.end);
# a timing/scheduling race that flakes under JRuby threading. Observed failing on Rails 7.2;
# 8.0 passes, so only excluded there.
exclude :test_no_locks_no_wait, 'flaky concurrency/timing assertion under JRuby threads' if ActiveRecord::VERSION::MAJOR < 8
