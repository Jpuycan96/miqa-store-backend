package com.miqa.store.export;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit TEST only, AFTER V9. No Boot, migrations, catalog fixtures or ERP calls. */
class RequestExportPersistenceIT {
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private Connection first;
    private Connection second;
    private RequestExportService service;
    private final String prefix="export-it-"+UUID.randomUUID();
    private final List<String> ids=new ArrayList<>();
    private final Instant at=Instant.parse("2400-01-01T00:00:00Z").plusSeconds(new Random().nextInt(1_000_000));
    @BeforeEach void setup() throws Exception {
        String password=System.getenv("TEST_DB_PASSWORD");
        if(password==null || password.isBlank()) throw new IllegalStateException("TEST_DB_PASSWORD required");
        dataSource=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55432/miqa_store_test_db","miqa_store_local",password);
        jdbc=new JdbcTemplate(dataSource);
        service=new RequestExportService(new RequestExportRepository(jdbc),dataSource);
        first=dataSource.getConnection(); first.setAutoCommit(false);
        second=dataSource.getConnection(); second.setAutoCommit(false);
    }
    @AfterEach void cleanup() throws Exception {
        try {
            if(first!=null) try { first.rollback(); } finally { first.close(); }
        } finally {
            if(second!=null) try { second.rollback(); } finally { second.close(); }
            if(jdbc!=null) for(String id:ids) {
                jdbc.update("DELETE FROM quote_request_items WHERE request_id=?",id);
                jdbc.update("DELETE FROM quote_request_v2_items WHERE request_id=?",id);
                jdbc.update("DELETE FROM quote_requests WHERE id=?",id);
            }
        }
    }
    private String insert(Connection connection,String suffix) throws Exception {
        String id=prefix+suffix; ids.add(id);
        try(var statement=connection.prepareStatement("""
                INSERT INTO quote_requests(id, reference_number, reference, contact_name, contact_phone,
                    idempotency_key, request_hash, created_at, updated_at)
                SELECT ?, n, 'MIQA-' || CASE WHEN n < 1000000 THEN lpad(n::text,6,'0') ELSE n::text END,
                    'Synthetic export TEST','999999999',?,repeat('a',64),?,?
                FROM nextval('quote_request_reference_seq') AS numbers(n)
                """)) {
            statement.setString(1,id); statement.setObject(2,UUID.randomUUID());
            statement.setTimestamp(3,Timestamp.from(at)); statement.setTimestamp(4,Timestamp.from(at)); statement.executeUpdate();
        }
        return id;
    }
    @Test void commitOrderDiffersFromReferenceAndReconciliationFindsLateCommit() throws Exception {
        String a=insert(first,"a");
        String b=insert(second,"b"); String c=insert(second,"c"); second.commit();
        var page=service.list(null,at.toString(),at.plusSeconds(1).toString(),1);
        assertThat(page.requests()).extracting(RequestExportDtos.Summary::id).containsExactly(b);
        first.commit();
        var continued=service.list(page.nextCursor(),null,null,null);
        assertThat(continued.requests()).extracting(RequestExportDtos.Summary::id).containsExactly(c);
        assertThat(service.list(page.nextCursor(),null,null,null)).isEqualTo(continued);
        var reconciled=service.list(null,at.toString(),at.plusSeconds(1).toString(),100);
        assertThat(reconciled.requests()).extracting(RequestExportDtos.Summary::id).containsExactly(a,b,c);
    }
    @Test void historicalTablesRoundTripWithoutReadingCurrentCatalog() throws Exception {
        String id=insert(first,"snapshot"); first.commit();
        var mapper=JsonMapper.builder().build();
        jdbc.update("""
                INSERT INTO quote_request_items(id,request_id,position,product_id,product_name,product_slug,sale_type,
                    quantity,unit_label,width_meters,height_meters,area_square_meters,snapshot)
                VALUES (?, ?, 1, 'legacy-product','Historical legacy','old-slug','AREA',2,'unidad',1,2,2,CAST(? AS jsonb))
                """,UUID.randomUUID().toString(),id,mapper.writeValueAsString(RequestExportServiceTest.legacy()));
        for(int position=2;position<=3;position++) jdbc.update("""
                INSERT INTO quote_request_v2_items(id,request_id,position,product_id,snapshot)
                VALUES (?, ?, ?, 'banner',CAST(? AS jsonb))
                """,UUID.randomUUID().toString(),id,position,mapper.writeValueAsString(RequestExportServiceTest.erp(position==3)));
        var detail=service.detail(id);
        assertThat(detail.items()).extracting(RequestExportDtos.Item::position).containsExactly(1,2,3);
        assertThat(detail.items().getFirst().legacy()).isEqualTo(RequestExportServiceTest.legacy());
        assertThat(detail.items().get(1).erp().pricing()).isNull();
        assertThat(detail.items().get(2).erp().pricing()).isEqualTo(RequestExportServiceTest.erp(true).pricing());
    }
}
