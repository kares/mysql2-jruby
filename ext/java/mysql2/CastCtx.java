package mysql2;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mysql.cj.result.Field;
import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.jruby.RubyFixnum;
import org.jruby.RubyTime;
import org.jruby.ext.bigdecimal.RubyBigDecimal;
import org.jruby.ext.date.RubyDate;
import org.jruby.ext.date.RubyDateTime;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

/**
 * Per-result casting context: uilt once per Mysql2::Result so that {@link Caster}
 * path avoids repeated constant lookups.
 */
final class CastCtx {
    final Ruby runtime;
    final ThreadContext context;
    final boolean castBooleans;
    final boolean dbTimezoneLocal;
    final Boolean appTimezoneLocal; // "local" | "utc" | null

    final Encoding defaultInternal; // Encoding.default_internal, or null

    public CastCtx(Ruby runtime, boolean castBooleans, String dbTimezone, String appTimezone) {
        this.runtime = runtime;
        this.context = runtime.getCurrentContext();
        this.castBooleans = castBooleans;
        this.dbTimezoneLocal = (dbTimezone == null || dbTimezone.isEmpty()) ? true : dbTimezone.equals("local");
        this.appTimezoneLocal = appTimezone == null ? null : appTimezone.equals("local");

        this.defaultInternal = runtime.getDefaultInternalEncoding();

        this.floatZero = runtime.newFloat(0);
    }

    private transient final IRubyObject floatZero;

    IRubyObject floatZero() {
        return floatZero;
    }

    IRubyObject bigDecimal(byte[] val) {
        return new RubyBigDecimal(runtime, new BigDecimal(new String(val, StandardCharsets.US_ASCII)));
    }

    IRubyObject bigDecimalZero() {
        return new RubyBigDecimal(runtime, BigDecimal.ZERO);
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

    RubyTime newTime(int year, int month, int day, int hour, int minute, int second, long usec) {
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

    IRubyObject newTimeInAppZone(int year, int month, int day, int hour, int minute, int second, long usec) {
        RubyTime time = newTime(year, month, day, hour, minute, second, usec);
        if (appTimezoneLocal == null) return time;
        if (appTimezoneLocal) return time.localtime(context); // time.localtime
        return time.gmtime(context); // time.utc
    }

    private transient RubyClass dateTimeClass;

    private RubyClass getDateTimeClass() {
        if (dateTimeClass == null) {
            this.dateTimeClass = (RubyClass) runtime.getObject().getConstant("DateTime");
        }
        return dateTimeClass;
    }

    RubyDateTime newDateTime(int year, int month, int day, int hour, int min, int sec) {
        return RubyDateTime.civil(context, getDateTimeClass(), new IRubyObject[] {
                runtime.newFixnum(year), runtime.newFixnum(month), runtime.newFixnum(day),
                runtime.newFixnum(hour), runtime.newFixnum(min), runtime.newFixnum(sec),
                dbOffset() });
    }

    IRubyObject newDateTimeInAppZone(int year, int month, int day, int hour, int min, int sec) {
        RubyDateTime time = newDateTime(year, month, day, hour, min, sec);
        if (appTimezoneLocal == null) return time;
        IRubyObject[] appOffset = appTimezoneLocal ?
                new IRubyObject[] { localOffset() } : IRubyObject.NULL_ARRAY;
        return time.new_offset(context, appOffset);
    }

    private transient IRubyObject dbOffset;

    IRubyObject dbOffset() {
        if (dbOffset == null) {
            dbOffset = dbTimezoneLocal ? localOffset() : RubyFixnum.zero(runtime);
        }
        return dbOffset;
    }

    private transient IRubyObject localOffset;

    IRubyObject localOffset() {
        if (localOffset == null) {
            localOffset = Client.localOffset(context);
        }
        return localOffset;
    }

    private transient RubyClass errorClass;

    RubyClass getErrorClass() {
        if (errorClass == null) {
            this.errorClass = Client.errorClass(runtime);
        }
        return errorClass;
    }

    static final Map<String, Encoding> encodingCache = new ConcurrentHashMap<>();

    /** Ruby encoding for a string field, from the driver's charset name (cached). */
    Encoding encodingFor(Field field) {
        final String encodingName = field.getEncoding();
        if (encodingName == null) return UTF8Encoding.INSTANCE;
        Encoding encoding = encodingCache.get(encodingName);
        if (encoding == null) {
            encoding = resolveEncoding(runtime, encodingName);
            encodingCache.put(encodingName, encoding);
        }
        return encoding;
    }

    private static Encoding resolveEncoding(Ruby runtime, String name) {
        try {
            Encoding encoding = runtime.getEncodingService().getEncodingFromString(name);
            if (encoding != null) return encoding;
        } catch (RuntimeException ignore) {
            // fall through to UTF-8
        }
        return UTF8Encoding.INSTANCE;
    }
}
