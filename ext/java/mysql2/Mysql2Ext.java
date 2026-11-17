// SPDX-FileCopyrightText: Copyright (c) 2026 Karol Bucek
// SPDX-License-Identifier: LGPL-3.0-or-later

package mysql2;

import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.jruby.RubyModule;
import org.jruby.runtime.load.BasicLibraryService;

// Loaded from lib/mysql2.rb via JRuby::Util.load_ext('mysql2.Mysql2Ext'),
// which instantiates this class (no-arg ctor) and calls basicLoad(runtime).
public class Mysql2Ext implements BasicLibraryService {
    public boolean basicLoad(Ruby runtime) {
        RubyModule Mysql2 = runtime.getOrCreateModule("Mysql2");

        RubyClass Client = Mysql2.defineClassUnder("Client", runtime.getObject(), mysql2.Client.ALLOCATOR);
        Client.defineAnnotatedMethods(mysql2.Client.class);
        mysql2.Client.defineConstants(runtime, Client);

        RubyClass Result = Mysql2.defineClassUnder("Result", runtime.getObject(), mysql2.Result.ALLOCATOR);
        Result.includeModule(runtime.getEnumerable());
        Result.defineAnnotatedMethods(mysql2.Result.class);

        RubyClass Statement = Mysql2.defineClassUnder("Statement", runtime.getObject(), mysql2.Statement.ALLOCATOR);
        Statement.defineAnnotatedMethods(mysql2.Statement.class);

        return true;
    }
}
