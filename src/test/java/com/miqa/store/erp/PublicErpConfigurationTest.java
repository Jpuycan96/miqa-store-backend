package com.miqa.store.erp;

import com.miqa.store.error.CatalogNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import tools.jackson.databind.json.JsonMapper;
import java.sql.ResultSet;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PublicErpConfigurationTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final PublicErpConfiguration service = new PublicErpConfiguration(jdbc, mapper);
    private void row(boolean bound, boolean active, String state, String payload) throws Exception {
        var rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn("banner");
        when(rs.getString("name")).thenReturn("Banner editorial");
        when(rs.getString("slug")).thenReturn("banner");
        when(rs.getString("bound_product")).thenReturn(bound ? "banner" : null);
        when(rs.getBoolean("active")).thenReturn(active);
        when(rs.getString("sync_state")).thenReturn(state);
        when(rs.getString("erp_service_id")).thenReturn("17");
        when(rs.getString("payload")).thenReturn(payload);
        when(rs.getString("erp_category_id")).thenReturn("3");
        when(jdbc.query(anyString(), any(RowMapper.class), eq("banner")))
                .thenAnswer(call -> bound && active && "AVAILABLE".equals(state) ? List.of(((RowMapper<?>)call.getArgument(1)).mapRow(rs, 0)) : List.of());
    }
    @Test void unboundIsLegacy() throws Exception {
        row(false, false, null, null);
        assertThatThrownBy(() -> service.configuration("banner")).isInstanceOf(CatalogNotFoundException.class);
    }
    @Test void activeAvailableReturnsOnlyPublicAllowlist() throws Exception {
        row(true, true, "AVAILABLE", ErpCatalogTest.JSON);
        var config = service.configuration("banner");
        assertThat(config.mode()).isEqualTo("ERP");
        assertThat(config.configuration().materiales()).hasSize(1);
        var json = mapper.readTree(mapper.writeValueAsString(config));
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("mode", "erpServiceId", "catalogRevision", "configurationVersion", "configuration");
        assertThat(json.toString()).doesNotContain("apiKey", "X-ERP-Service-Key", "baseUrl", "payload", "evaluatedAt");
    }
    @Test void disabledOrAbsentFromListingCannotFallBackToLegacy() throws Exception {
        row(true, false, "AVAILABLE", ErpCatalogTest.JSON);
        assertThatThrownBy(() -> service.configuration("banner")).isInstanceOf(CatalogNotFoundException.class);
        row(true, true, "PENDING_REVALIDATION", ErpCatalogTest.JSON);
        assertThatThrownBy(() -> service.configuration("banner")).isInstanceOf(CatalogNotFoundException.class);
    }
    @Test void corruptUnsupportedAndMismatchedProjectionFailClosed() throws Exception {
        for (String payload : List.of("{}", ErpCatalogTest.JSON.replace("cantidad\"]", "desconocido\"]"),
                ErpCatalogTest.JSON.replace("\"erpServiceId\":\"17\"", "\"erpServiceId\":\"18\""))) {
            row(true, true, "AVAILABLE", payload);
            assertThatThrownBy(() -> service.configuration("banner")).isInstanceOf(CatalogNotFoundException.class);
        }
    }
    @Test void hiddenOrAbsentProductHasNoConfiguration() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq("missing"))).thenReturn(List.of());
        assertThatThrownBy(() -> service.configuration("missing")).isInstanceOf(CatalogNotFoundException.class);
        verify(jdbc).query(contains("p.published AND c.active"), any(RowMapper.class), eq("missing"));
    }
}
