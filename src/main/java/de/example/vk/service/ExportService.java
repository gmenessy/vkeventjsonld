package de.example.vk.service;

import de.example.vk.util.ConfigVk;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;

/** CSV-Export veröffentlichter Events (Spezifikation 27.4/15.5). */
@Service
public class ExportService {

    private final NamedParameterJdbcTemplate jdbc;

    public ExportService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String exportPublishedCsv() {
        final StringBuilder sb = new StringBuilder();
        sb.append("title,shortDescription,startAt,endAt,category,placeName,locality,"
                + "organizerName,attendanceMode,isFree,minPrice,keywords\n");
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("m", ConfigVk.requireMandant()).addValue("vk", ConfigVk.requireVkId());
        jdbc.query(
                "SELECT e.TITLE, e.SHORT_DESCRIPTION, e.START_AT, e.END_AT, e.ATTENDANCE_MODE, "
              + "       e.IS_ACCESSIBLE_FOR_FREE, p.NAME AS PLACE_NAME, a.LOCALITY, "
              + "       (SELECT c.NAME FROM VK_EVENT_CATEGORY ec JOIN VK_CATEGORY c ON c.ID=ec.CATEGORY_ID "
              + "         WHERE ec.EVENT_ID=e.ID ORDER BY ec.PRIMARY_FLAG DESC FETCH FIRST 1 ROWS ONLY) AS CAT, "
              + "       (SELECT pa.DISPLAY_NAME FROM VK_EVENT_PARTY_ROLE r JOIN VK_PARTY pa ON pa.ID=r.PARTY_ID "
              + "         WHERE r.EVENT_ID=e.ID AND r.ROLE_TYPE='ORGANIZER' FETCH FIRST 1 ROWS ONLY) AS ORG, "
              + "       (SELECT MIN(o.PRICE) FROM VK_OFFER o WHERE o.EVENT_ID=e.ID) AS MINPRICE, "
              + "       (SELECT LISTAGG(k.NAME, '; ') WITHIN GROUP (ORDER BY k.NAME) FROM VK_EVENT_KEYWORD ek "
              + "         JOIN VK_KEYWORD k ON k.ID=ek.KEYWORD_ID WHERE ek.EVENT_ID=e.ID) AS KW "
              + "FROM VK_EVENT e LEFT JOIN VK_PLACE p ON p.ID=e.PLACE_ID LEFT JOIN VK_ADDRESS a ON a.ID=p.ADDRESS_ID "
              + "WHERE e.MANDANT_ID=:m AND e.VK_ID=:vk AND e.WORKFLOW_STATUS='PUBLISHED' "
              + "ORDER BY e.START_AT",
                p, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    Timestamp start = rs.getTimestamp("START_AT");
                    Timestamp end = rs.getTimestamp("END_AT");
                    sb.append(csv(rs.getString("TITLE"))).append(',')
                      .append(csv(rs.getString("SHORT_DESCRIPTION"))).append(',')
                      .append(csv(start == null ? "" : start.toString())).append(',')
                      .append(csv(end == null ? "" : end.toString())).append(',')
                      .append(csv(rs.getString("CAT"))).append(',')
                      .append(csv(rs.getString("PLACE_NAME"))).append(',')
                      .append(csv(rs.getString("LOCALITY"))).append(',')
                      .append(csv(rs.getString("ORG"))).append(',')
                      .append(csv(rs.getString("ATTENDANCE_MODE"))).append(',')
                      .append("Y".equals(rs.getString("IS_ACCESSIBLE_FOR_FREE")) ? "true" : "false").append(',')
                      .append(csv(rs.getString("MINPRICE"))).append(',')
                      .append(csv(rs.getString("KW"))).append('\n');
                });
        return sb.toString();
    }

    /** RFC-4180-konformes CSV-Feld: in Anführungszeichen, eingebettete Quotes verdoppeln. */
    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
