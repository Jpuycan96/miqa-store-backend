package com.miqa.store.export;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import java.sql.Timestamp;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestExportRepositoryTest {
    @Test void queryUsesPersistedTupleBoundsAndLookaheadWithNoOffsetOrReferenceCursor() {
        var jdbc=mock(JdbcTemplate.class);
        var repository=new RequestExportRepository(jdbc);
        var query=new RequestExportCursor(RequestExportServiceTest.START,RequestExportServiceTest.END,RequestExportServiceTest.START,"b",2);
        repository.list(query);
        var call=mockingDetails(jdbc).getInvocations().iterator().next();
        String sql=(String)call.getRawArguments()[0];
        assertThat(sql).contains("created_at >= ? AND created_at < ?","(created_at, id COLLATE \"C\") > (?, ? COLLATE \"C\")",
                "ORDER BY created_at ASC, id COLLATE \"C\" ASC LIMIT ?").doesNotContain("OFFSET","reference_number","request_hash","idempotency_key","contact_");
        Object[] params=(Object[])call.getRawArguments()[2];
        assertThat(params).containsExactly(Timestamp.from(query.from()),Timestamp.from(query.before()),Timestamp.from(query.afterTime()),"b",3);
    }
    @Test void detailQueriesOnlyHistoricalTablesAndDoesNotSelectInternalHashes() {
        var jdbc=mock(JdbcTemplate.class,invocation -> invocation.getMethod().getName().equals("query") ? List.of() : RETURNS_DEFAULTS.answer(invocation));
        var repository=new RequestExportRepository(jdbc);
        repository.header("request"); repository.items("request");
        var sql=mockingDetails(jdbc).getInvocations().stream().map(i -> (String)i.getRawArguments()[0]).toList();
        assertThat(sql.getFirst()).doesNotContain("SELECT *","request_hash","idempotency_key");
        assertThat(sql.getLast()).contains("quote_request_items","quote_request_v2_items","UNION ALL","ORDER BY position, export_id")
                .doesNotContain("JOIN products","erp_catalog","product_erp_bindings");
    }
}
