package com.miqa.store;

import com.miqa.store.admin.*;
import com.miqa.store.catalog.*;
import com.miqa.store.config.MediaProperties;
import com.miqa.store.error.ApiExceptionHandler;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real controller/service/validation and repository mocks, without JDBC or Flyway. */
class AdminCategoryEditorialHttpTest {
    private final CategoryRepository categories=mock(CategoryRepository.class);
    private final EntityManager em=mock(EntityManager.class,RETURNS_DEEP_STUBS);
    private final AdminCatalogService service=new AdminCatalogService(categories,mock(CategorySlugAliasRepository.class),
            mock(ProductRepository.class),em,new MediaProperties(),mock(ProductImageStorage.class));
    private final org.springframework.test.web.servlet.MockMvc mvc=MockMvcBuilders.standaloneSetup(new AdminCatalogController(service))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    private Category category(){
        var c=spy(new Category());c.setId("c");c.setName("ERP name");c.setSlug("stable");
        doReturn("3").when(c).getErpCategoryId();when(categories.findById("c")).thenReturn(Optional.of(c));return c;
    }
    @Test void updatesExistingEditorialFieldsThroughExistingPut() throws Exception {
        category();mvc.perform(put("/api/admin/categories/c").contentType("application/json")
                .content("{\"name\":\"ERP name\",\"description\":\"Editorial\",\"catalogHeadline\":\"Headline\",\"catalogDescription\":\"Summary\",\"active\":false,\"displayOrder\":4}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("c"))
                .andExpect(jsonPath("$.erpCategoryId").value("3")).andExpect(jsonPath("$.slug").value("stable"))
                .andExpect(jsonPath("$.description").value("Editorial")).andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.displayOrder").value(4));verify(em).flush();
    }
    @Test void rejectsIdentityInjectionAndInvalidEditorialValuesBeforePersistence() throws Exception {
        for(String field:java.util.List.of("id","slug","erpCategoryId","erp_category_id","products")){
            mvc.perform(put("/api/admin/categories/c").contentType("application/json")
                    .content("{\"name\":\"ERP name\",\"active\":true,\"displayOrder\":0,\""+field+"\":\"tampered\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/admin/categories/c").contentType("application/json")
                .content("{\"name\":\"ERP name\",\"active\":true,\"displayOrder\":-1,\"catalogHeadline\":\"<b>bad</b>\"}"))
                .andExpect(status().isBadRequest());verifyNoInteractions(categories);verify(em,never()).flush();
    }
    @Test void rejectsNameChangeAndDeletionOfErpCategory() throws Exception {
        category();mvc.perform(put("/api/admin/categories/c").contentType("application/json")
                .content("{\"name\":\"Changed\",\"active\":true,\"displayOrder\":0}"))
                .andExpect(status().isConflict());mvc.perform(delete("/api/admin/categories/c"))
                .andExpect(status().isConflict());verify(em,never()).flush();
    }
}
