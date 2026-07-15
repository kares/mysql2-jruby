package mysql2;

import java.nio.charset.StandardCharsets;

import org.jcodings.Encoding;
import org.jcodings.specific.ASCIIEncoding;
import org.jruby.Ruby;
import org.jruby.RubyString;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;
import org.jruby.util.ConvertBytes;
import org.jruby.util.ConvertDouble;
import org.jruby.util.io.EncodingUtils;

/**
 * Casts raw text-protocol wire bytes to Ruby objects, faithful to mysql2's
 * ext/mysql2/result.c text path (rb_mysql_result_fetch_row). Timezone and
 * boolean options and cached class handles come from {@link CastCtx}.
 */
public final class Caster {

    private static final long MYSQL2_MIN_TIME = 2678400L;
    private static final long MYSQL2_MAX_TIME = 315578267999L;

    static IRubyObject byteCast(CastCtx ctx, TextRow row, int col, com.mysql.cj.result.Field field) {
        Ruby runtime = ctx.runtime;
        byte[] b = row.getBytes(col);
        if (b == null) return runtime.getNil();

        switch (field.getMysqlTypeId()) {
            case Field.TYPE_NULL:
                return runtime.getNil();
            case Field.TYPE_BIT:
                if (ctx.castBooleans && field.getLength() == 1) return runtime.newBoolean(b.length > 0 && b[0] == 1);
                return newBinaryString(runtime, b);
            case Field.TYPE_TINY:
                if (ctx.castBooleans && field.getLength() == 1) return runtime.newBoolean(b.length > 0 && b[0] != '0');
                return parseInteger(runtime, b);
            case Field.TYPE_SHORT:
            case Field.TYPE_LONG:
            case Field.TYPE_INT24:
            case Field.TYPE_LONGLONG:
            case Field.TYPE_YEAR:
                return parseInteger(runtime, b);
            case Field.TYPE_DECIMAL:
            case Field.TYPE_NEWDECIMAL:
                if (field.getDecimals() == 0) return parseInteger(runtime, b);
                if (parseDouble(b) == 0.0) return ctx.bigDecimalZero();
                return ctx.bigDecimal(b);
            case Field.TYPE_FLOAT:
            case Field.TYPE_DOUBLE: {
                double d = parseDouble(b);
                return d == 0.0 ? ctx.floatZero() : runtime.newFloat(d);
            }
            case Field.TYPE_TIME:
                return parseTime(ctx, b);
            case Field.TYPE_TIMESTAMP:
            case Field.TYPE_DATETIME:
                return parseDateTime(ctx, b);
            case Field.TYPE_DATE:
            case Field.TYPE_NEWDATE:
                return parseDate(ctx, b);
            default:
                return asString(ctx, b, field);
        }
    }

    /** cast:false -- every non-NULL value as a String with the field's encoding */
    public static IRubyObject raw(CastCtx ctx, TextRow row, int col, com.mysql.cj.result.Field field) {
        byte[] b = row.getBytes(col);
        if (b == null) return ctx.runtime.getNil();
        return asString(ctx, b, field);
    }

    /**
     * String value tagged with the field's encoding, then transcoded.
     * Mirrors mysql2's <code>mysql2_set_field_string_encoding</code>
     */
    private static RubyString asString(CastCtx ctx, byte[] bytes, com.mysql.cj.result.Field field) {
        if (mysql2.Field.isBinary(field)) return newBinaryString(ctx.runtime, bytes);

        Encoding encoding = ctx.encodingFor(field);
        RubyString str = ctx.runtime.newString(new ByteList(bytes, encoding, false));
        if (ctx.defaultInternal != null && ctx.defaultInternal != encoding) {
            return EncodingUtils.strConvEnc(ctx.context, str, encoding, ctx.defaultInternal);
        }
        return str;
    }

    // --- temporal ------------------------------------------------------------

    private static IRubyObject parseTime(CastCtx ctx, byte[] b) {
        int[] p = {0};
        int hour = scanUInt(b, p, 2);
        if (hour < 0 || !expect(b, p, ':')) return ctx.runtime.getNil();
        int min = scanUInt(b, p, 2);
        if (min < 0 || !expect(b, p, ':')) return ctx.runtime.getNil();
        int sec = scanUInt(b, p, 2);
        if (sec < 0) return ctx.runtime.getNil();
        int usec = scanFraction(b, p);
        return ctx.newTimeInAppZone(2000, 1, 1, hour, min, sec, usec);
    }

    private static IRubyObject parseDateTime(CastCtx ctx, byte[] b) {
        int[] p = {0};
        int year = scanUInt(b, p, 4);
        if (year < 0 || !expect(b, p, '-')) return ctx.runtime.getNil();
        int month = scanUInt(b, p, 2);
        if (month < 0 || !expect(b, p, '-')) return ctx.runtime.getNil();
        int day = scanUInt(b, p, 2);
        if (day < 0 || !expect(b, p, ' ')) return ctx.runtime.getNil();
        int hour = scanUInt(b, p, 2);
        if (hour < 0 || !expect(b, p, ':')) return ctx.runtime.getNil();
        int min = scanUInt(b, p, 2);
        if (min < 0 || !expect(b, p, ':')) return ctx.runtime.getNil();
        int sec = scanUInt(b, p, 2);
        if (sec < 0) return ctx.runtime.getNil();
        int usec = scanFraction(b, p);

        long seconds = (long) year * 31557600L +
                (long) month * 2592000L +
                (long) day * 86400L +
                (long) hour * 3600L +
                (long) min * 60L + sec;
        if (seconds == 0) return ctx.runtime.getNil();
        if (month < 1 || day < 1) {
            throw ctx.runtime.newRaiseException(ctx.getErrorClass(), "Invalid date: " + new String(b, StandardCharsets.US_ASCII));
        }
        if (seconds < MYSQL2_MIN_TIME || seconds > MYSQL2_MAX_TIME) {
            return ctx.newDateTimeInAppZone(year, month, day, hour, min, sec);
        }
        return ctx.newTimeInAppZone(year, month, day, hour, min, sec, usec);
    }

    private static IRubyObject parseDate(CastCtx ctx, byte[] b) {
        int[] p = {0};
        int year = scanUInt(b, p, 4);
        if (year < 0 || !expect(b, p, '-')) return ctx.runtime.getNil();
        int month = scanUInt(b, p, 2);
        if (month < 0 || !expect(b, p, '-')) return ctx.runtime.getNil();
        int day = scanUInt(b, p, 2);
        if (day < 0) return ctx.runtime.getNil();
        if (year + month + day == 0) return ctx.runtime.getNil();
        if (month < 1 || day < 1) {
            throw ctx.runtime.newRaiseException(ctx.getErrorClass(), "Invalid date: " + new String(b, StandardCharsets.US_ASCII));
        }
        return ctx.newDate(year, month, day);
    }

    // --- numeric -------------------------------------------------------------

    // rb_cstr2inum equivalent: byte[] -> Fixnum/Bignum
    private static IRubyObject parseInteger(Ruby runtime, byte[] bytes) {
        return ConvertBytes.byteListToInum(runtime, wrapBytes(bytes), 10, false);
    }

    // strtod equivalent: byte[] -> double
    private static double parseDouble(byte[] bytes) {
        // JRuby 10.0+ only:
        // return ConvertDouble.byteListToDouble(wrapBytes(bytes), false);
        return new ConvertDouble.DoubleConverter().parse(wrapBytes(bytes), false, true);
    }

    private static ByteList wrapBytes(byte[] bytes) {
        return new ByteList(bytes, ASCIIEncoding.INSTANCE, false);
    }

    private static RubyString newBinaryString(Ruby runtime, byte[] bytes) {
        return RubyString.newString(runtime, wrapBytes(bytes));
    }

    // --- ascii scanning (emulates sscanf token counting) ---------------------

    /** Read up to maxDigits ASCII digits at p[0]; advance p[0]; -1 if none. */
    private static int scanUInt(byte[] b, int[] p, int maxDigits) {
        int i = p[0], start = i, v = 0;
        int end = Math.min(b.length, i + maxDigits);
        while (i < end && b[i] >= '0' && b[i] <= '9') { v = v * 10 + (b[i] - '0'); i++; }
        if (i == start) return -1;
        p[0] = i;
        return v;
    }

    private static boolean expect(byte[] b, int[] p, char c) {
        if (p[0] < b.length && b[p[0]] == c) { p[0]++; return true; }
        return false;
    }

    /** Optional ".ffffff" -> microseconds, left-aligned to 6 digits. */
    private static int scanFraction(byte[] b, int[] p) {
        if (p[0] >= b.length || b[p[0]] != '.') return 0;
        p[0]++;
        int v = 0, count = 0;
        while (p[0] < b.length && count < 6 && b[p[0]] >= '0' && b[p[0]] <= '9') {
            v = v * 10 + (b[p[0]] - '0'); p[0]++; count++;
        }
        while (count++ < 6) v *= 10;
        return v;
    }
}
