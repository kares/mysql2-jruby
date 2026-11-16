
## 0.5.7.0 (RC1)

First release; a JRuby-native re-implementation of mysql2 0.5.7's API on top of
MySQL Connector/J, aimed at running ActiveRecord's `mysql2` adapter unmodified.

We've tested and pass 98% of Rails' activerecord test suite for 7.2, 8.0 and 8.1. 

- implemented `Mysql2::Client`, `Mysql2::Result`, `Mysql2::Statement` and 
  `Mysql2::Field`; reading rows off text protocol (rather than through JDBC)
- support mysql2's casting rules and options (`:as`, `:symbolize_keys`, `:cast`,
  `:cast_booleans`, `:database_timezone`, `:application_timezone`, `:cache_rows`)
