package com.miqa.store.erp;

import com.miqa.store.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ErpLegacyBindingHttpTest {
 @Test void directHttpCannotBindLegacyAndNeverCallsRemoteErpOrWrites() throws Exception {
  var jdbc=mock(JdbcTemplate.class);
  var repository=spy(new ErpCatalogRepository(jdbc,JsonMapper.builder().build()));
  doReturn(true).when(repository).trySyncLock();doReturn(true).when(repository).lockProduct("legacy");
  doReturn(true).when(repository).serviceExists("17");
  var client=mock(ErpCatalogClient.class);
  var service=new ErpCatalogService(client,repository,mock(PlatformTransactionManager.class));
  var mvc=MockMvcBuilders.standaloneSetup(new ErpCatalogController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
  mvc.perform(put("/api/admin/erp-catalog/bindings/legacy").contentType("application/json").content("{\"erpServiceId\":\"17\",\"active\":true}"))
   .andExpect(status().isConflict());
  verify(jdbc,never()).update(anyString(),any(Object[].class));verifyNoInteractions(client);
 }
 @Test void erpBindingStillUsesExistingUpsert() {
  var jdbc=mock(JdbcTemplate.class);var repository=new ErpCatalogRepository(jdbc,JsonMapper.builder().build());
  when(jdbc.queryForObject(contains("catalog_mode = 'ERP'"),eq(Boolean.class),eq("erp"))).thenReturn(true);
  repository.bind("erp",new ErpCatalogDtos.BindingInput("17",false));
  verify(jdbc).update(contains("INSERT INTO product_erp_bindings"),eq("erp"),eq("17"),eq(false));
 }
}
