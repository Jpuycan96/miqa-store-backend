package com.miqa.store;
import com.miqa.store.admin.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
 "spring.flyway.enabled=false","spring.sql.init.mode=never","spring.jpa.hibernate.ddl-auto=validate",
 "app.erp.webhook.receive-enabled=false","app.erp.webhook.processing-enabled=false"})
@org.springframework.context.annotation.Import(AdminApiTest.GuardedDatabase.class)
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
@ActiveProfiles("test")
class AdminApiTest {
 @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
 static class GuardedDatabase {
  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
  javax.sql.DataSource guardedTestDataSource() {
   String password=System.getenv("TEST_DB_PASSWORD");
   if(password==null||password.isBlank())throw new IllegalStateException("TEST_DB_PASSWORD required");
   return new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55432/miqa_store_test_db","miqa_store_local",password){
    @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
     var c=super.getConnection();
     try {com.miqa.store.erp.PostgresTestIsolation.verify(c);c.rollback();c.setAutoCommit(true);return c;}
     catch(java.sql.SQLException|RuntimeException|AssertionError ex){c.close();throw ex;}
    }
    @Override public java.sql.Connection getConnection(String username,String password) throws java.sql.SQLException {
     throw new java.sql.SQLException("Alternate TEST credentials forbidden");
    }
   };
  }
 }
 private final String suffix=UUID.randomUUID().toString();
 private final String username="admin-it-"+suffix;
 private final String categoryId="admin-c-"+suffix;
 private final String productId="admin-p-"+suffix;
 private final String productSlug="admin-test-"+suffix;
 private final String categorySlug="admin-cat-"+suffix;
 private final String categoryErpId=new java.math.BigInteger(UUID.randomUUID().toString().replace("-",""),16).add(java.math.BigInteger.ONE).toString();
 private boolean categoryOwned,productOwned,userOwned;
 @org.junit.jupiter.api.io.TempDir static java.nio.file.Path mediaDirectory;
 @org.springframework.test.context.DynamicPropertySource
 static void media(org.springframework.test.context.DynamicPropertyRegistry registry){
  registry.add("app.media.storage-path",()->mediaDirectory.toString());
  registry.add("app.media.base-url",()->"https://api.example.test/media");
 }
 @LocalServerPort int port;
 @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper mapper; @Autowired AdminUserRepository users; @Autowired PasswordEncoder passwords; @Autowired JwtEncoder encoder;
 private final HttpClient client=HttpClient.newHttpClient();private String token,userId;
 @BeforeEach void setup() throws Exception {
  assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("miqa_store_test_db");
  assertThat(users.findByUsername(username)).isEmpty();
  var user=new AdminUser(username,passwords.encode("Test-only-password-2026"));userId=user.getId();
  users.saveAndFlush(user);userOwned=true;
  token=json(send("POST","/api/admin/auth/login",Map.of("username",username,"password","Test-only-password-2026"),null),200).get("token").asText();
 }
 @AfterEach void cleanup(){
  if(!categoryOwned&&!productOwned&&!userOwned)return;
  new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status->{
   if(productOwned){
    // Unknown bindings/history must never be deleted to force fixture cleanup.
    assertThat(jdbc.queryForObject("SELECT count(*) FROM product_erp_bindings WHERE product_id=?",Integer.class,productId)).isZero();
    assertThat(jdbc.update("DELETE FROM products WHERE id=? AND category_id=? AND catalog_mode='ERP'",productId,categoryId)).isEqualTo(1);
   }
   if(categoryOwned){
    assertThat(jdbc.queryForObject("SELECT count(*) FROM category_slug_aliases WHERE category_id=?",Integer.class,categoryId)).isZero();
    assertThat(jdbc.update("DELETE FROM categories WHERE id=? AND erp_category_id=? AND slug=?",categoryId,categoryErpId,categorySlug)).isEqualTo(1);
   }
   if(userOwned)assertThat(jdbc.update("DELETE FROM admin_users WHERE id=? AND username=?",userId,username)).isEqualTo(1);
  });
  assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE id=?",Integer.class,productId)).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE id=?",Integer.class,categoryId)).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_users WHERE id=?",Integer.class,userId)).isZero();
 }
 private HttpResponse<String> send(String method,String path,Object body,String bearer) throws Exception {
  var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
  if(bearer!=null)r.header("Authorization","Bearer "+bearer);
  if(body!=null)r.header("Content-Type","application/json");
  return client.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
 }
 private JsonNode json(HttpResponse<String> r,int status){assertThat(r.statusCode()).withFailMessage("Expected %s, got %s: %s",status,r.statusCode(),r.body()).isEqualTo(status);return mapper.readTree(r.body());}
 private JsonNode call(String method,String path,Object body,int status)throws Exception{return json(send(method,"/api/admin"+path,body,token),status);}
 private Map<String,Object> category(String name){var category=new HashMap<String,Object>();category.put("name",name);category.put("description","Local test");category.put("catalogHeadline","  Make it visible.  ");category.put("catalogDescription","  Commercial description.  ");category.put("active",true);category.put("displayOrder",50);return category;}
 private Map<String,Object> product(String slug,String category){var p=new HashMap<String,Object>();p.put("name","Admin test product");p.put("slug",slug);p.put("categoryId",category);p.put("shortDescription","Test");p.put("description","Test detail");p.put("saleType","QUANTITY");p.put("unitLabel","unidad");p.put("minQuantity",1);p.put("quantityStep",1);p.put("published",false);p.put("featured",false);p.put("displayOrder",50);return p;}
 // Fixtures bypass retired creation endpoints; synthetic ERP records remain unavailable publicly without bindings.
 private String createProduct() {
  new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status->{
   assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE id=? OR slug=? OR erp_category_id=?",Integer.class,categoryId,categorySlug,categoryErpId)).isZero();
   assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE id=? OR slug=?",Integer.class,productId,productSlug)).isZero();
   assertThat(jdbc.queryForObject("SELECT count(*) FROM category_slug_aliases WHERE slug IN (?,?)",Integer.class,categorySlug,productSlug)).isZero();
   jdbc.update("INSERT INTO categories(id,name,slug,erp_category_id) VALUES (?,'ERP fixture',?,?)",categoryId,categorySlug,categoryErpId);
   jdbc.update("INSERT INTO products(id,category_id,name,slug,short_description,description,catalog_mode) VALUES (?,?,'ERP fixture',?,'Test','Test detail','ERP')",productId,categoryId,productSlug);
  });
  categoryOwned=true;productOwned=true;return productId;
 }
 private byte[] imageBytes(String format)throws Exception{
  var out=new java.io.ByteArrayOutputStream();
  javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),format,out);
  return out.toByteArray();
 }
 private HttpResponse<String> upload(String pid,String name,String type,byte[] bytes,String fields,String bearer)throws Exception{
  String boundary="miqa-test-boundary";
  var out=new java.io.ByteArrayOutputStream();
  out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: "+type+"\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  out.write(bytes);
  if(fields!=null)out.write(("\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"primaryImage\"\r\n\r\n"+fields).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  out.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/admin/products/"+pid+"/images/upload"))
   .header("Content-Type","multipart/form-data; boundary="+boundary);
  if(bearer!=null)request.header("Authorization","Bearer "+bearer);
  return client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
 }
 @Test void uploadsJpegPngAndPromotesPrimaryWithSafePublicPaths()throws Exception{
  String pid=createProduct();
  var first=json(upload(pid,"../../photo.JPG","image/jpeg",imageBytes("jpg"),null,token),201);
  assertThat(first.get("primaryImage").asBoolean()).isTrue();
  assertThat(first.get("displayOrder").asInt()).isZero();
  String url=first.get("url").asText();
  assertThat(url).matches("https://api.example.test/media/products/"+pid+"/[0-9a-f-]+\\.jpg");
  assertThat(first.get("publicUrl").asText()).isEqualTo(url);
  var file=mediaDirectory.resolve(url.substring("https://api.example.test/media/".length()));
  assertThat(java.nio.file.Files.readAllBytes(file)).isEqualTo(imageBytes("jpg"));
  var second=json(upload(pid,"photo.png","image/png",imageBytes("png"),"true",token),201);
  assertThat(second.get("displayOrder").asInt()).isEqualTo(1);
  var list=call("GET","/products/"+pid+"/images",null,200);
  assertThat(list.get(0).get("primaryImage").asBoolean()).isFalse();
  assertThat(list.get(1).get("primaryImage").asBoolean()).isTrue();
  call("POST","/products/"+pid+"/images/"+second.get("id").asText()+"/remove",null,204);
  list=call("GET","/products/"+pid+"/images",null,200);
  assertThat(list.size()).isEqualTo(1);assertThat(list.get(0).get("primaryImage").asBoolean()).isTrue();
  assertThat(java.nio.file.Files.exists(mediaDirectory.resolve(second.get("url").asText().substring("https://api.example.test/media/".length())))).isFalse();
  assertThat(java.nio.file.Files.exists(file)).isTrue();
 }
 @Test void uploadRejectsInvalidEmptyOversizedMissingAndUnauthenticated()throws Exception{
  String pid=createProduct();
  json(upload(pid,"photo.gif","image/gif",imageBytes("png"),null,token),400);
  json(upload(pid,"photo.jpg","image/png",imageBytes("png"),null,token),400);
  json(upload(pid,"photo.png","image/png","not an image".getBytes(),null,token),400);
  json(upload(pid,"photo.png","image/png",new byte[0],null,token),400);
  json(upload(pid,"photo.png","image/png",new byte[5*1024*1024+1],null,token),413);
  json(upload("missing-product","photo.png","image/png",imageBytes("png"),null,token),404);
  json(upload(pid,"photo.png","image/png",imageBytes("png"),null,null),401);
 }
 @Test void imageLimitIncludesLegacyAndManualImagesAndRemovalKeepsLegacyReferences()throws Exception{
  String pid=createProduct(),path="/products/"+pid+"/images";
  var legacy=call("POST",path,Map.of("url","/images/hero/sample.png","altText","Legacy","displayOrder",4,"primaryImage",true),201);
  assertThat(legacy.get("publicUrl").asText()).isEqualTo("/images/hero/sample.png");
  json(upload(pid,"a.png","image/png",imageBytes("png"),null,token),201);
  json(upload(pid,"b.jpg","image/jpeg",imageBytes("jpg"),null,token),201);
  json(upload(pid,"c.png","image/png",imageBytes("png"),null,token),409);
  call("POST",path,Map.of("url","/images/products/old.png","altText","","displayOrder",0,"primaryImage",false),409);
  call("POST","/products/missing-product/images/"+legacy.get("id").asText()+"/remove",null,404);
  call("POST",path+"/"+legacy.get("id").asText()+"/remove",null,204);
  assertThat(jdbc.queryForObject("select url from product_images where id=?",String.class,legacy.get("id").asText())).isEqualTo("/images/hero/sample.png");
  assertThat(call("GET",path,null,200).get(0).get("primaryImage").asBoolean()).isTrue();
  json(upload(pid,"new.png","image/png",imageBytes("png"),null,token),201);
  call("PATCH","/products/"+pid+"/published",Map.of("published",true),200);
  json(send("GET","/api/public/products/"+productSlug,null,null),404);
  var publicProduct=call("GET","/products/"+pid,null,200);
  assertThat(publicProduct.get("images").size()).isEqualTo(3);
  assertThat(publicProduct.get("images").toString()).doesNotContain("/images/hero/sample.png");
 }
 @Autowired AdminCatalogService catalogService;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
 @Test void rollbackRemovesUploadedFileAndPreservesDatabase()throws Exception{
  String pid=createProduct();byte[] png=imageBytes("png");
  var transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
  String url=transaction.execute(status->{
   var result=catalogService.upload(pid,new org.springframework.mock.web.MockMultipartFile("file","photo.png","image/png",png),"Vista frontal",7,false);
   assertThat(result.altText()).isEqualTo("Vista frontal");assertThat(result.displayOrder()).isEqualTo(7);
   assertThat(java.nio.file.Files.exists(mediaDirectory.resolve(result.url().substring("https://api.example.test/media/".length())))).isTrue();
   status.setRollbackOnly();return result.url();
  });
  assertThat(java.nio.file.Files.exists(mediaDirectory.resolve(url.substring("https://api.example.test/media/".length())))).isFalse();
  assertThat(call("GET","/products/"+pid+"/images",null,200).size()).isZero();
 }
 @Test void concurrentUploadsCannotExceedThreeImages()throws Exception{
  String pid=createProduct();
  json(upload(pid,"a.png","image/png",imageBytes("png"),null,token),201);
  json(upload(pid,"b.png","image/png",imageBytes("png"),null,token),201);
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
   var one=pool.submit(()->upload(pid,"c.png","image/png",imageBytes("png"),null,token).statusCode());
   var two=pool.submit(()->upload(pid,"d.png","image/png",imageBytes("png"),null,token).statusCode());
   assertThat(List.of(one.get(),two.get())).containsExactlyInAnyOrder(201,409);
  }
  assertThat(call("GET","/products/"+pid+"/images",null,200).size()).isEqualTo(3);
 }
 @Test void authProtectsAllAdminResourcesAndLeavesPublicOpen()throws Exception{
  for(String path:List.of("/products","/categories","/auth/me","/products/banner/images","/products/banner/materials","/products/banner/extras"))json(send("GET","/api/admin"+path,null,null),401);
  json(send("DELETE","/api/admin/products/banner/materials/banner-13",null,null),401);
  assertThat(call("GET","/auth/me",null,200).get("username").asText()).isEqualTo(username);
  assertThat(call("GET","/auth/me",null,200).has("passwordHash")).isFalse();
  json(send("POST","/api/admin/auth/login",Map.of("username",username,"password","wrong"),null),401);
  json(send("POST","/api/admin/auth/login",Map.of("username","missing","password","wrong"),null),401);
  json(send("GET","/api/admin/products",null,token+"invalid"),401);
  json(send("GET","/api/public/products",null,null),200);
  assertThat(users.findByUsername(username).orElseThrow().getPasswordHash()).startsWith("$2");
 }
 @Test void expiredAndInactiveUserTokensAreRejected()throws Exception{
  var claims=JwtClaimsSet.builder().issuer("miqa-store-admin").audience(List.of("miqa-store-admin-api")).subject(userId).issuedAt(Instant.now().minusSeconds(7200)).expiresAt(Instant.now().minusSeconds(3600)).build();
  String expired=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
  json(send("GET","/api/admin/auth/me",null,expired),401);
  jdbc.update("update admin_users set active=false where id=?",userId);
  json(send("GET","/api/admin/auth/me",null,token),401);
  json(send("POST","/api/admin/auth/login",Map.of("username",username,"password","Test-only-password-2026"),null),401);
 }
 @Test void retiredCategoryCreationDeletionAndTechnicalOperationsReturnConflict() throws Exception {
  String pid=createProduct();
  call("POST","/categories",category("Retired"),409);
  call("DELETE","/categories/"+categoryId,null,409);
  call("POST","/products",product("unused-"+suffix,categoryId),409);
  for(String kind:List.of("materials","extras")) {
   call("POST","/products/"+pid+"/"+kind,Map.of("name","Retired","active",true,"displayOrder",0),409);
  }
  assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE id=?",Integer.class,pid)).isEqualTo(1);
 }
 @Test void productLifecycleVisibilityFiltersAndSeo()throws Exception{
  String id=createProduct();String url="/products/"+id;
  var edit=product(productSlug,categoryId);for(String field:List.of("saleType","unitLabel","minQuantity","quantityStep"))edit.put(field,null);edit.put("name","Edited product");edit.put("seoTitle","SEO test");edit.put("seoDescription","SEO description");
  assertThat(call("PUT",url,edit,200).get("seoTitle").asText()).isEqualTo("SEO test");
  call("POST","/products",edit,409);call("GET",url,null,200);
  json(send("GET","/api/public/products/"+productSlug,null,null),404);
  call("PATCH",url+"/published",Map.of("published",true),200);call("PATCH",url+"/featured",Map.of("featured",true),200);
  assertThat(call("GET","/products?category="+categoryId+"&search=Edited&published=true&featured=true",null,200).size()).isEqualTo(1);
  json(send("GET","/api/public/products/"+productSlug,null,null),404);
  call("PATCH",url+"/published",Map.of("published",false),200);
  json(send("GET","/api/public/products/"+productSlug,null,null),404);
 }
 @Test void imagesKeepOnlyOnePrimaryAndValidateReferences()throws Exception{
  String id=createProduct(),url="/products/"+id+"/images";var input=new HashMap<String,Object>(Map.of("url","products/test.webp","altText","Test image","primaryImage",true,"displayOrder",0));
  String first=call("POST",url,input,201).get("id").asText();input.put("url","https://media.example.test/second.webp");
  String second=call("POST",url,input,201).get("id").asText();
  assertThat(call("GET",url,null,200).valueStream().filter(i->i.get("primaryImage").asBoolean()).count()).isEqualTo(1);
  call("PATCH",url+"/"+first+"/primary",Map.of("primaryImage",true),200);
  input.put("primaryImage",false);input.put("altText","Edited alt");call("PUT",url+"/"+second,input,200);
  for(String bad:List.of("javascript:alert(1)","../private","//evil.example/x")){input.put("url",bad);call("POST",url,input,400);}
  call("PATCH","/products/missing-product/images/"+first+"/primary",Map.of("primaryImage",true),404);
 }
 @Test void repeatedLoginFailuresAreLimited() throws Exception {
  for(int i=0;i<5;i++)json(send("POST","/api/admin/auth/login",Map.of("username","rate-limit-"+suffix,"password","wrong"),null),401);
  json(send("POST","/api/admin/auth/login",Map.of("username","rate-limit-"+suffix,"password","wrong"),null),429);
 }
 @Test void adminCorsAllowsDeletePostAndPutOnlyForConfiguredOrigin()throws Exception{
  String allowedOrigin="http://localhost:4200",path="/api/admin/products/banner/materials/banner-13";
  for(String method:List.of("DELETE","POST","PUT")){
   var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Origin",allowedOrigin).header("Access-Control-Request-Method",method).header("Access-Control-Request-Headers","authorization,content-type").method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build();
   var response=client.send(req,HttpResponse.BodyHandlers.ofString());
   assertThat(response.statusCode()).isEqualTo(200);
   assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(allowedOrigin);
   assertThat(response.headers().firstValue("Access-Control-Allow-Methods").orElse("")).contains(method);
   assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase()).contains("authorization");
  }
  var rejected=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Origin","https://outside.example").header("Access-Control-Request-Method","DELETE").header("Access-Control-Request-Headers","authorization").method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build();
  var response=client.send(rejected,HttpResponse.BodyHandlers.ofString());
  assertThat(response.statusCode()).isEqualTo(403);
  assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
  json(send("DELETE",path,null,null),401);
 }
}
