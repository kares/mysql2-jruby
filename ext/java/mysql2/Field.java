// SPDX-FileCopyrightText: Copyright (c) 2026 Karol Bucek
// SPDX-License-Identifier: LGPL-3.0-or-later

package mysql2;

/** MySQL wire field metadata subset needed for casting (mirrors MYSQL_FIELD). */
final class Field {
    // enum_field_types
    public static final int TYPE_DECIMAL = 0, TYPE_TINY = 1, TYPE_SHORT = 2, TYPE_LONG = 3,
            TYPE_FLOAT = 4, TYPE_DOUBLE = 5, TYPE_NULL = 6, TYPE_TIMESTAMP = 7, TYPE_LONGLONG = 8,
            TYPE_INT24 = 9, TYPE_DATE = 10, TYPE_TIME = 11, TYPE_DATETIME = 12, TYPE_YEAR = 13,
            TYPE_NEWDATE = 14, TYPE_VARCHAR = 15, TYPE_BIT = 16, TYPE_JSON = 245, TYPE_NEWDECIMAL = 246,
            TYPE_ENUM = 247, TYPE_SET = 248, TYPE_TINY_BLOB = 249, TYPE_MEDIUM_BLOB = 250,
            TYPE_LONG_BLOB = 251, TYPE_BLOB = 252, TYPE_VAR_STRING = 253, TYPE_STRING = 254,
            TYPE_GEOMETRY = 255;

    public static final int UNSIGNED_FLAG = 32;
    public static final int BINARY_FLAG = 128;
    public static final int ENUM_FLAG = 256;
    public static final int SET_FLAG = 2048;
    public static final int MYSQL2_BINARY_CHARSET = 63;
    // mysql2 hardcodes this; char/varchar lengths are byte lengths divided by it
    private static final long MAX_BYTES_PER_CHAR = 3;

    public static boolean isBinary(com.mysql.cj.result.Field field) {
        final int charsetnr = field.getCollationIndex();
        return ((field.getFlags() & BINARY_FLAG) != 0 && charsetnr == MYSQL2_BINARY_CHARSET) || charsetnr == 0;
    }

    /**
     * MySQL type descriptor string for Mysql2::Result#field_types, e.g.
     * "int(11)", "varchar(13)", "decimal(10,3)", "text(1020)", "json".
     * Mirrors mysql2's rb_mysql_result_fetch_field_type. NOTE: Connector/J exposes
     * the numeric precision (digit count), not MySQL's display width, so for a
     * default-width signed integer the display width is restored (see intWidth);
     * DECIMAL uses the driver's precision directly rather than libmysql arithmetic
     */
    public static String typeName(final int type,
                                  final long length,
                                  final int decimals,
                                  final short flags,
                                  final int collationIndex) {
        boolean binaryCharset = collationIndex == MYSQL2_BINARY_CHARSET;
        switch (type) {
            case TYPE_NULL:      return "null";
            case TYPE_TINY:      return "tinyint(" + intWidth(length, flags, 3, 4) + ")";
            case TYPE_SHORT:     return "smallint(" + intWidth(length, flags, 5, 6) + ")";
            case TYPE_YEAR:      return "year(" + length + ")";
            case TYPE_INT24:     return "mediumint(" + intWidth(length, flags, 7, 9) + ")";
            case TYPE_LONG:      return "int(" + intWidth(length, flags, 10, 11) + ")";
            case TYPE_LONGLONG:  return "bigint(" + intWidth(length, flags, 19, 20) + ")";
            case TYPE_FLOAT:     return "float(" + length + "," + decimals + ")";
            case TYPE_DOUBLE:    return "double(" + length + "," + decimals + ")";
            case TYPE_TIME:      return "time";
            case TYPE_DATE:
            case TYPE_NEWDATE:   return "date";
            case TYPE_DATETIME:  return "datetime";
            case TYPE_TIMESTAMP: return "timestamp";
            case TYPE_DECIMAL:
            case TYPE_NEWDECIMAL: return "decimal(" + length + "," + decimals + ")";
            case TYPE_STRING:
                if ((flags & ENUM_FLAG) != 0) return "enum";
                if ((flags & SET_FLAG) != 0) return "set";
                if (binaryCharset) return "binary(" + length + ")";
                return "char(" + (length / MAX_BYTES_PER_CHAR) + ")";
            case TYPE_VAR_STRING:
                if (binaryCharset) return "varbinary(" + length + ")";
                return "varchar(" + (length / MAX_BYTES_PER_CHAR) + ")";
            case TYPE_VARCHAR:   return "varchar(" + (length / MAX_BYTES_PER_CHAR) + ")";
            case TYPE_TINY_BLOB: return "tinyblob";
            case TYPE_BLOB:
                if (binaryCharset) {
                    if (length == 255L) return "tinyblob";
                    if (length == 65535L) return "blob";
                    if (length == 16777215L) return "mediumblob";
                    return "longblob";
                }
                if (length == 255L * MAX_BYTES_PER_CHAR) return "tinytext";
                if (length == 65535L * MAX_BYTES_PER_CHAR) return "text";
                if (length == 16777215L * MAX_BYTES_PER_CHAR) return "mediumtext";
                if (length == 4294967295L) return "longtext";
                return "text(" + length + ")";
            case TYPE_MEDIUM_BLOB: return "mediumblob";
            case TYPE_LONG_BLOB:   return "longblob";
            case TYPE_BIT:         return "bit(" + length + ")";
            case TYPE_SET:         return "set";
            case TYPE_ENUM:        return "enum";
            case TYPE_GEOMETRY:    return "geometry";
            case TYPE_JSON:        return "json";
            default:               return "unknown";
        }
    }

    /**
     * Restore libmysql's integer display width.
     * Connector/J reports the numeric precision (digit count of the signed range);
     * for a default-width signed column that is one short of MySQL's display width
     * (which reserves a slot for the sign; MEDIUMINT is two short)
     */
    private static long intWidth(final long length, final short flags,
                                 final long signedPrecision, final long displayWidth) {
        boolean unsigned = (flags & UNSIGNED_FLAG) != 0;
        return (!unsigned && length == signedPrecision) ? displayWidth : length;
    }

    static String typeName(final com.mysql.cj.result.Field field) {
        return typeName(field.getMysqlTypeId(), field.getLength(), field.getDecimals(), field.getFlags(), field.getCollationIndex());
    }

    /**
     * MySQL type id, declared length, flags and collation, equivalent to libmysqlclient's MYSQL_FIELD
     */
    /*
    public static Field fromNative(com.mysql.cj.result.Field f) {
        // getMysqlTypeId() returns the true type regardless of tinyInt1isBit, and getLength() preserves e.g. TINYINT(1)
        return new Field(f.getMysqlTypeId(), f.getDecimals(), f.getLength(),
                f.getFlags(), f.getCollationIndex(), f.getColumnLabel(), f.getEncoding());
    } */

    /** Map a JDBC column to the MySQL field type the caster switches on. */
    /*
    public static Field fromMeta(ResultSetMetaData md, int col) throws SQLException {
        int sqlType = md.getColumnType(col);
        int len = md.getColumnDisplaySize(col);
        int decimals = md.getScale(col);
        String name = md.getColumnLabel(col);
        switch (sqlType) {
            case Types.BIGINT:   return new Field(TYPE_LONGLONG, 0, len, 0, 63, name);
            case Types.INTEGER:  return new Field(TYPE_LONG, 0, len, 0, 63, name);
            case Types.SMALLINT: return new Field(TYPE_SHORT, 0, len, 0, 63, name);
            case Types.TINYINT:  return new Field(TYPE_TINY, 0, len, 0, 63, name);
            case Types.FLOAT:
            case Types.REAL:
            case Types.DOUBLE:   return new Field(TYPE_DOUBLE, decimals, len, 0, 63, name);
            case Types.DECIMAL:
            case Types.NUMERIC:  return new Field(TYPE_NEWDECIMAL, decimals, len, 0, 63, name);
            case Types.TIMESTAMP:return new Field(TYPE_DATETIME, 0, len, 0, 63, name);
            case Types.DATE:     return new Field(TYPE_DATE, 0, len, 0, 63, name);
            case Types.TIME:     return new Field(TYPE_TIME, 0, len, 0, 63, name);
            case Types.BIT:
            case Types.BOOLEAN:  return new Field(TYPE_BIT, 0, len, 0, 63, name);
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:     return new Field(TYPE_BLOB, 0, len, BINARY_FLAG, 63, name);
            default:             return new Field(TYPE_VAR_STRING, 0, len, 0, 33, name);
        }
    } */
}
