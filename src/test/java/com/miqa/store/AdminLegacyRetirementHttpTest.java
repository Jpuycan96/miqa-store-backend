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
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real controller, validation and service; no application/DB/filesystem writes. */
class AdminLegacyRetirementHttpTest {
 private final ProductRepository products=mock(ProductRepository.class);
 private final CategoryRepository categories=mock(CategoryRepository.class);
 private final EntityManager em=mock(EntityManager.class,RETURNS_DEEP_STUBS);
 private final ProductImageStorage storage=mock(ProductImageStorage.class);
 private final AdminCatalogService service=new AdminCatalogService(categories,mock(CategorySlugAliasRepository.class),products,em,new MediaProperties(),storage);
 private final org.springframework.test.web.servlet.MockMvc mvc=MockMvcBuilders.standaloneSetup(new AdminCatalogController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
 private final String input="{\"categoryId\":\"c\",\"name\":\"Editorial\",\"slug\":\"editorial\",\"shortDescription\":\"Short\",\"description\":\"Description\",\"featured\":true,\"published\":true,\"displayOrder\":2,\"seoTitle\":\"SEO\",\"seoDescription\":\"SEO summary\"}";
 private Product fixture(String mode){var p=new Product();p.setId("p");p.setCatalogMode(mode);var c=new Category();c.setId("c");p.setCategory(c);when(products.findById("p")).thenReturn(Optional.of(p));when(categories.findById("c")).thenReturn(Optional.of(c));return p;}
 @Test void legacyProductMutationsIncludingImagesAreRejected() throws Exception {
  var p=fixture("LEGACY");
  mvc.perform(put("/api/admin/products/p").contentType("application/json").content(input)).andExpect(status().isConflict());
  for(String flag:java.util.List.of("published","featured")) mvc.perform(patch("/api/admin/products/p/"+flag).contentType("application/json").content("{\""+flag+"\":true}")).andExpect(status().isConflict());
  String image="{\"url\":\"/images/test.png\",\"altText\":\"Test\",\"primaryImage\":true,\"displayOrder\":0}";
  mvc.perform(post("/api/admin/products/p/images").contentType("application/json").content(image)).andExpect(status().isConflict());
  mvc.perform(put("/api/admin/products/p/images/i").contentType("application/json").content(image)).andExpect(status().isConflict());
  mvc.perform(patch("/api/admin/products/p/images/i/primary").contentType("application/json").content("{\"primaryImage\":true}")).andExpect(status().isConflict());
  mvc.perform(post("/api/admin/products/p/images/i/remove")).andExpect(status().isConflict());
  mvc.perform(multipart("/api/admin/products/p/images/upload").file("file",new byte[]{1})).andExpect(status().isConflict());
  assertThat(p.isPublished()).isFalse();assertThat(p.isFeatured()).isFalse();verify(em,never()).flush();verifyNoInteractions(storage);
 }
 @Test void creationAndLegacyCategoryChangesAreRejected() throws Exception {
  fixture("LEGACY");String category="{\"name\":\"Legacy\",\"active\":true,\"displayOrder\":0}";
  mvc.perform(post("/api/admin/products").contentType("application/json").content(input)).andExpect(status().isConflict());
  mvc.perform(post("/api/admin/categories").contentType("application/json").content(category)).andExpect(status().isConflict());
  mvc.perform(put("/api/admin/categories/c").contentType("application/json").content(category)).andExpect(status().isConflict());
  mvc.perform(delete("/api/admin/categories/c")).andExpect(status().isConflict());
  mvc.perform(patch("/api/admin/categories/c/active").contentType("application/json").content("{\"active\":false}")).andExpect(status().isConflict());
  verify(em,never()).flush();verify(categories,never()).delete(any());
 }
 @Test void technicalOptionMutationsAreRejectedForBothModes() throws Exception {
  for(String mode:java.util.List.of("ERP","LEGACY")) {
   fixture(mode);
   for(String kind:java.util.List.of("materials","extras")) {
    String url="/api/admin/products/p/"+kind;String body="{\"name\":\"Option\",\"active\":true,\"displayOrder\":0}";
    mvc.perform(post(url).contentType("application/json").content(body)).andExpect(status().isConflict());
    mvc.perform(put(url+"/o").contentType("application/json").content(body)).andExpect(status().isConflict());
    mvc.perform(patch(url+"/o/active").contentType("application/json").content("{\"active\":false}")).andExpect(status().isConflict());
   }
   mvc.perform(delete("/api/admin/products/p/materials/o")).andExpect(status().isConflict());
  }
  verify(em,never()).flush();
 }
 @Test void erpEditorialSeoAndPublicationContinueThroughSharedEndpoints() throws Exception {
  fixture("ERP");mvc.perform(put("/api/admin/products/p").contentType("application/json").content(input)).andExpect(status().isOk()).andExpect(jsonPath("$.seoTitle").value("SEO")).andExpect(jsonPath("$.description").value("Description"));
  mvc.perform(patch("/api/admin/products/p/published").contentType("application/json").content("{\"published\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.published").value(false));
  mvc.perform(patch("/api/admin/products/p/featured").contentType("application/json").content("{\"featured\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.featured").value(false));
 }
 @Test void erpImageCreationRemainsAvailable() throws Exception {
  fixture("ERP");
  mvc.perform(post("/api/admin/products/p/images").contentType("application/json")
   .content("{\"url\":\"/images/erp.png\",\"altText\":\"ERP image\",\"primaryImage\":true,\"displayOrder\":0}"))
   .andExpect(status().isCreated()).andExpect(jsonPath("$.publicUrl").value("/images/erp.png"));
  verify(em).persist(any(ProductImage.class));
 }
}
