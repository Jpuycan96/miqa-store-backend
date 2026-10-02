package com.miqa.store.export;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.miqa.store.export.RequestExportDtos.*;

@Repository
public class RequestExportRepository {
    private final JdbcTemplate jdbc;
    public RequestExportRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    Instant now() { return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class)).toInstant(); }
    List<Summary> list(RequestExportCursor cursor) {
        String sql="""
                SELECT id, reference, origin, status, created_at, updated_at FROM quote_requests
                WHERE created_at >= ? AND created_at < ?
                """;
        var args=new ArrayList<Object>();
        args.add(Timestamp.from(cursor.from())); args.add(Timestamp.from(cursor.before()));
        if(cursor.afterTime()!=null) {
            sql+=" AND (created_at, id COLLATE \"C\") > (?, ? COLLATE \"C\")";
            args.add(Timestamp.from(cursor.afterTime())); args.add(cursor.afterId());
        }
        sql+=" ORDER BY created_at ASC, id COLLATE \"C\" ASC LIMIT ?";
        args.add(cursor.limit()+1);
        return jdbc.query(sql,(rs,row) -> summary(rs),args.toArray());
    }
    Optional<Header> header(String id) {
        return jdbc.query("""
                SELECT id, reference, origin, status, created_at, updated_at,
                       contact_name, contact_phone, contact_email, notes FROM quote_requests WHERE id = ?
                """,(rs,row) -> new Header(summary(rs),new Contact(rs.getString("contact_name"),rs.getString("contact_phone"),
                rs.getString("contact_email")),rs.getString("notes")),id).stream().findFirst();
    }
    List<StoredItem> items(String id) {
        return jdbc.query("""
                SELECT 'v1:' || id AS export_id, position, product_id, snapshot::text AS snapshot
                FROM quote_request_items WHERE request_id = ?
                UNION ALL
                SELECT 'v2:' || id AS export_id, position, product_id, snapshot::text AS snapshot
                FROM quote_request_v2_items WHERE request_id = ?
                ORDER BY position, export_id
                """,(rs,row) -> new StoredItem(rs.getString("export_id"),rs.getInt("position"),
                rs.getString("product_id"),rs.getString("snapshot")),id,id);
    }
    private static Summary summary(ResultSet rs) throws SQLException {
        return new Summary(rs.getString("id"),rs.getString("reference"),rs.getString("origin"),rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant());
    }
    record Header(Summary summary, Contact contact, String notes) {
        @Override public String toString() { return "ExportHeader[REDACTED]"; }
    }
    record StoredItem(String id, int position, String productId, String snapshot) {
        @Override public String toString() { return "StoredExportItem[REDACTED]"; }
    }
}
