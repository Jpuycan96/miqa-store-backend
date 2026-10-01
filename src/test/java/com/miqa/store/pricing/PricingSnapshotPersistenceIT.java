package com.miqa.store.pricing;

import com.miqa.store.quote.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit TEST-only test after V9. No Boot, Flyway, ERP network or development fixtures. */
class PricingSnapshotPersistenceIT {
    private Connection connection;
    private JdbcTemplate jdbc;
    private final JsonMapper mapper=JsonMapper.builder().build();
    @BeforeEach void setup() throws Exception {
        String password=System.getenv("TEST_DB_PASSWORD");
        if(password==null || password.isBlank()) throw new IllegalStateException("TEST_DB_PASSWORD required");
        connection=DriverManager.getConnection("jdbc:postgresql://127.0.0.1:55432/miqa_store_test_db","miqa_store_local",password);
        connection.setAutoCommit(false);
        jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
    }
    @AfterEach void cleanup() throws Exception {
        if(connection!=null) try { connection.rollback(); } finally { connection.close(); }
    }
    @Test void roundTripsPriceAndQuoteRequiredInHistoricalJsonb() {
        var repository=new QuoteRequestRepository(jdbc,mapper);
        for(var state:List.of(PricingDtos.Status.PRICE_AVAILABLE,PricingDtos.Status.QUOTE_REQUIRED)) {
            var key=UUID.randomUUID();
            var canonical=new QuoteRequestCanonicalizer.Canonical(key,"f".repeat(64),new QuoteRequestDtos.Submission(
                    new QuoteRequestDtos.Contact("Synthetic TEST","999999999",null),null,List.of()));
            var price=state==PricingDtos.Status.PRICE_AVAILABLE
                    ? new PricingDtos.Historical(state,"73.25","PEN",true,"TOTAL_LINEA","M2",
                            new PricingDtos.BillableBase("3.0","M2"),"synthetic-revision",Instant.parse("2026-09-30T12:00:00Z"))
                    : new PricingDtos.Historical(state,null,null,null,null,null,null,"synthetic-revision",Instant.EPOCH);
            var snapshot=ErpPricingTest.snapshot().withPricing(price);
            var receipt=repository.insertV2(canonical,List.of(new QuoteV2Dtos.StoredItem(snapshot.productId(),snapshot)));
            String json=jdbc.queryForObject("""
                    SELECT i.snapshot::text FROM quote_request_v2_items i JOIN quote_requests q ON q.id=i.request_id
                    WHERE q.idempotency_key=?
                    """,String.class,key);
            var stored=mapper.readValue(json,QuoteV2Dtos.ErpSnapshot.class);
            assertThat(stored.pricing()).isEqualTo(price);
            assertThat(repository.find(key).orElseThrow()).isEqualTo(receipt);
            assertThat(json).doesNotContain("apiKey","X-ERP-Service-Key","baseUrl");
        }
    }
}
