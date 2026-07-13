package mysql2;

import java.util.HashMap;
import java.util.Map;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.jruby.RubyTime;
import org.jruby.ext.date.RubyDate;
import org.jruby.ext.date.RubyDateTime;
import org.jruby.runtime.Helpers;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

/**
 * Per-result casting context: cached Ruby class handles and the query's
 * timezone/boolean options. Built once per Mysql2::Result so the hot
 * {@link Caster} path avoids repeated constant lookups.
 */
public final class CastCtx {
    final Ruby runtime;
    final ThreadContext context;
    final boolean castBooleans;
    final boolean dbTimezoneLocal;
    final String appTimezone; // "local" | "utc" | null

    final RubyClass dateTimeClass;
    final RubyClass errorClass;
    final IRubyObject topSelf;      // receiver for Kernel#BigDecimal
    final IRubyObject decimalZero;  // BigDecimal("0.0")
    final IRubyObject floatZero;    // 0.0
    final IRubyObject utcOffset;    // 0
    final IRubyObject localOffset;  // Rational from Mysql2::Client.local_offset

    final Encoding defaultInternal; // Encoding.default_internal, or null
    private final Map<String, Encoding> encCache = new HashMap<>();

    public CastCtx(Ruby runtime, boolean castBooleans, String dbTimezone, String appTimezone,
                   RubyClass errorClass, IRubyObject localOffset) {
        this.runtime = runtime;
        this.context = runtime.getCurrentContext();
        this.castBooleans = castBooleans;
        this.dbTimezoneLocal = (dbTimezone == null || dbTimezone.isEmpty()) ? true : dbTimezone.equals("local");
        this.appTimezone = appTimezone;
        this.errorClass = errorClass;
        this.dateTimeClass = (RubyClass) runtime.getObject().getConstant("DateTime");
        this.topSelf = runtime.getTopSelf();
        this.decimalZero = bigDecimal(runtime.newString("0.0"));
        this.floatZero = runtime.newFloat(0.0);
        this.utcOffset = runtime.newFixnum(0);
        this.localOffset = localOffset;
        this.defaultInternal = runtime.getDefaultInternalEncoding();
    }

    IRubyObject bigDecimal(IRubyObject arg) {
        return Helpers.invoke(context, topSelf, "BigDecimal", arg);
    }

    IRubyObject newDate(int year, int month, int day) {
        return RubyDate.civil(
                context,
                getDateClass(),
                runtime.newFixnum(year),
                runtime.newFixnum(month),
                runtime.newFixnum(day)
        );
    }

    private transient RubyClass dateClass;

    private RubyClass getDateClass() {
        if (dateClass == null) {
            this.dateClass = (RubyClass) runtime.getObject().getConstant("Date");
        }
        return dateClass;
    }

    RubyTime newTime(int year, int month, int day,
                        int hour, int minute, int second,
                        long usec) {
        if (dbTimezoneLocal) {
            return RubyTime.local(context, runtime.getTime(), new IRubyObject[] {
                    runtime.newFixnum(year), runtime.newFixnum(month), runtime.newFixnum(day),
                    runtime.newFixnum(hour), runtime.newFixnum(minute), runtime.newFixnum(second),
                    runtime.newFixnum(usec) });
        }
        return RubyTime.utc(context, runtime.getTime(), new IRubyObject[] {
                runtime.newFixnum(year), runtime.newFixnum(month), runtime.newFixnum(day),
                runtime.newFixnum(hour), runtime.newFixnum(minute), runtime.newFixnum(second),
                runtime.newFixnum(usec) });
    }

    RubyDateTime newDateTime(int year, int month, int day,
                             int hour, int minute, int second) {
        IRubyObject offset = dbTimezoneLocal ? localOffset : utcOffset;
        return RubyDateTime.civil(context, dateTimeClass, new IRubyObject[] {
                runtime.newFixnum(year), runtime.newFixnum(month), runtime.newFixnum(day),
                runtime.newFixnum(hour), runtime.newFixnum(minute), runtime.newFixnum(second),
                offset });
    }

    /** Ruby encoding for a string field, from the driver's charset name (cached). */
    Encoding encodingFor(Field f) {
        String name = f.encodingName;
        if (name == null) return UTF8Encoding.INSTANCE;
        Encoding enc = encCache.get(name);
        if (enc == null) {
            enc = resolveEncoding(name);
            encCache.put(name, enc);
        }
        return enc;
    }

    private Encoding resolveEncoding(String name) {
        try {
            Encoding e = runtime.getEncodingService().getEncodingFromString(name);
            if (e != null) return e;
        } catch (RuntimeException ignore) {
            // fall through to UTF-8
        }
        return UTF8Encoding.INSTANCE;
    }
}
