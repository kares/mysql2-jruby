# Changelog

## 0.5.7.0 (unreleased)

First release. A JRuby-native re-implementation of mysql2 0.5.7's API on top of
MySQL Connector/J, aimed at running ActiveRecord's stock `mysql2` adapter unmodified.

- `Mysql2::Client`, `Mysql2::Result`, `Mysql2::Statement` and `Mysql2::Field` in Java,
  reading rows off the text protocol rather than through JDBC's `ResultSet` getters.
- mysql2's casting rules and query options (`:as`, `:symbolize_keys`, `:cast`,
  `:cast_booleans`, `:database_timezone`, `:application_timezone`, `:cache_rows`).
- `mysql2/compat` registers a `mysql2` gem alias so the adapter's `gem "mysql2", "~> 0.5"`
  resolves when the gem is installed under a different name.
- Known gaps are listed under *Limitations* in the README.
