# SPDX-FileCopyrightText: Copyright (c) 2026 Karol Bucek
# SPDX-License-Identifier: LGPL-3.0-or-later

require 'date'
require 'bigdecimal'

require 'mysql2/version' unless defined? Mysql2::VERSION
require 'mysql2/error'
require 'mysql2/field'

connector_jar = ENV['RUBY_MYSQL2_CONNECTOR_JAR']
require connector_jar if connector_jar

begin
  java.lang.Class.forName('com.mysql.cj.jdbc.NonRegisteringDriver')
rescue java.lang.ClassNotFoundException
  fail LoadError, "MySQL Connector/J not found on the classpath"
end

load File.expand_path('mysql2.jar', File.dirname(__FILE__))
JRuby::Util.load_ext('mysql2.Mysql2Ext')
