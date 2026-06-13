package de.example.vk.util;

import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Liest CLOB-Spalten treiberunabhaengig als String (Oracle und H2). */
public final class ClobUtil {

    private ClobUtil() {
    }

    public static String readClob(ResultSet rs, String column) throws SQLException {
        Clob clob = rs.getClob(column);
        if (clob == null) {
            return null;
        }
        long length = clob.length();
        if (length == 0) {
            return "";
        }
        return clob.getSubString(1, (int) length);
    }
}
