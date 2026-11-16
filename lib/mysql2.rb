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
  JRuby::Util.load_java_class('com.mysql.cj.Constants')
rescue NameError
  begin
    require 'jar_dependencies' unless defined?(Jars)
    require_jar 'com.mysql', 'mysql-connector-j', Mysql2::MINIMAL_CONNECTOR_VERSION
  rescue LoadError, RuntimeError => e
    raise(e) if e.instance_of?(RuntimeError) && !e.message&.include?('LoadError')
    fail LoadError, "MySQL Connector/J not found on classpath " +
                    "(set RUBY_MYSQL2_CONNECTOR_JAR or add jar to class-path)"
  end
end

load File.expand_path('mysql2.jar', File.dirname(__FILE__))
JRuby::Util.load_ext('mysql2.Mysql2Ext')
