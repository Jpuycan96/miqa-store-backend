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

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminApiTest {
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
  cleanup();var user=users.saveAndFlush(new AdminUser("admin-test",passwords.encode("Test-only-password-2026")));userId=user.getId();
  token=json(send("POST","/api/admin/auth/login",Map.of("username","admin-test","password","Test-only-password-2026"),null),200).get("token").asText();
 }
 @AfterEach void cleanup(){jdbc.update("delete from products where slug like 'admin-test-%'");jdbc.update("delete from categories where slug like 'admin-test-%'");jdbc.update("delete from admin_users where username='admin-test'");}
 private HttpResponse<String> send(String method,String path,Object body,String bearer) throws Exception {
  var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
  if(bearer!=null)r.header("Authorization","Bearer "+bearer);
  if(body!=null)r.header("Content-Type","application/json");
  return client.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
 }
 private JsonNode json(HttpResponse<String> r,int status){assertThat(r.statusCode()).withFailMessage("Expected %s, got %s: %s",status,r.statusCode(),r.body()).isEqualTo(status);return mapper.readTree(r.body());}
 private JsonNode call(String method,String path,Object body,int status)throws Exception{return json(send(method,"/api/admin"+path,body,token),status);}
 private Map<String,Object> category(String slug){var category=new HashMap<String,Object>();category.put("name","Admin test category");category.put("slug",slug);category.put("description","Local test");category.put("catalogHeadline","  Make it visible.  ");category.put("catalogDescription","  Commercial description.  ");category.put("active",true);category.put("displayOrder",50);return category;}
 private Map<String,Object> product(String slug,String category){var p=new HashMap<String,Object>();p.put("name","Admin test product");p.put("slug",slug);p.put("categoryId",category);p.put("shortDescription","Test");p.put("description","Test detail");p.put("saleType","QUANTITY");p.put("unitLabel","unidad");p.put("minQuantity",1);p.put("quantityStep",1);p.put("published",false);p.put("featured",false);p.put("displayOrder",50);return p;}
 private String createProduct() throws Exception{return call("POST","/products",product("admin-test-product","imprenta-papeleria"),201).get("id").asText();}
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
  call("POST","/products/banner/images/"+legacy.get("id").asText()+"/remove",null,404);
  call("POST",path+"/"+legacy.get("id").asText()+"/remove",null,204);
  assertThat(jdbc.queryForObject("select url from product_images where id=?",String.class,legacy.get("id").asText())).isEqualTo("/images/hero/sample.png");
  assertThat(call("GET",path,null,200).get(0).get("primaryImage").asBoolean()).isTrue();
  json(upload(pid,"new.png","image/png",imageBytes("png"),null,token),201);
  call("PATCH","/products/"+pid+"/published",Map.of("published",true),200);
  var publicProduct=json(send("GET","/api/public/products/admin-test-product",null,null),200);
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
  assertThat(call("GET","/auth/me",null,200).get("username").asText()).isEqualTo("admin-test");
  assertThat(call("GET","/auth/me",null,200).has("passwordHash")).isFalse();
  json(send("POST","/api/admin/auth/login",Map.of("username","admin-test","password","wrong"),null),401);
  json(send("POST","/api/admin/auth/login",Map.of("username","missing","password","wrong"),null),401);
  json(send("GET","/api/admin/products",null,token+"invalid"),401);
  json(send("GET","/api/public/products",null,null),200);
  assertThat(users.findByUsername("admin-test").orElseThrow().getPasswordHash()).startsWith("$2");
 }
 @Test void expiredAndInactiveUserTokensAreRejected()throws Exception{
  var claims=JwtClaimsSet.builder().issuer("miqa-store-admin").audience(List.of("miqa-store-admin-api")).subject(userId).issuedAt(Instant.now().minusSeconds(7200)).expiresAt(Instant.now().minusSeconds(3600)).build();
  String expired=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
  json(send("GET","/api/admin/auth/me",null,expired),401);
  jdbc.update("update admin_users set active=false where id=?",userId);
  json(send("GET","/api/admin/auth/me",null,token),401);
  json(send("POST","/api/admin/auth/login",Map.of("username","admin-test","password","Test-only-password-2026"),null),401);
 }
 @Test void categoryLifecycleAndDuplicateSlug()throws Exception{
 var input=category("admin-test-category");String id=call("POST","/categories",input,201).get("id").asText();
  assertThat(call("GET","/categories",null,200).size()).isEqualTo(7);
  var created=call("GET","/categories/"+id,null,200);assertThat(created.get("catalogHeadline").asText()).isEqualTo("Make it visible.");assertThat(created.get("catalogDescription").asText()).isEqualTo("Commercial description.");input.put("name","Edited category");input.put("catalogHeadline","Updated headline");input.put("catalogDescription","Updated description");
  var updated=call("PUT","/categories/"+id,input,200);assertThat(updated.get("name").asText()).isEqualTo("Edited category");assertThat(updated.get("catalogHeadline").asText()).isEqualTo("Updated headline");assertThat(updated.get("catalogDescription").asText()).isEqualTo("Updated description");
  call("POST","/categories",input,409);input.put("slug","INVALID");call("POST","/categories",input,400);
  input.put("slug","admin-test-invalid-html");input.put("catalogHeadline","<b>Unsafe</b>");call("POST","/categories",input,400);
  input.put("catalogHeadline","a".repeat(201));call("POST","/categories",input,400);
  assertThat(call("PATCH","/categories/"+id+"/active",Map.of("active",false),200).get("active").asBoolean()).isFalse();
  call("DELETE","/categories/"+id,null,405);
 }
 @Test void productLifecycleVisibilityFiltersAndSeo()throws Exception{
  String id=createProduct();String url="/products/"+id;
  var edit=product("admin-test-product","imprenta-papeleria");edit.put("name","Edited product");edit.put("seoTitle","SEO test");edit.put("seoDescription","SEO description");
  assertThat(call("PUT",url,edit,200).get("seoTitle").asText()).isEqualTo("SEO test");
  call("POST","/products",edit,409);call("GET",url,null,200);
  json(send("GET","/api/public/products/admin-test-product",null,null),404);
  call("PATCH",url+"/published",Map.of("published",true),200);call("PATCH",url+"/featured",Map.of("featured",true),200);
  assertThat(call("GET","/products?category=imprenta-papeleria&search=Edited&published=true&featured=true",null,200).size()).isEqualTo(1);
  json(send("GET","/api/public/products/admin-test-product",null,null),200);
  call("PATCH",url+"/published",Map.of("published",false),200);
  json(send("GET","/api/public/products/admin-test-product",null,null),404);
 }
 @Test void saleTypesAndInvalidInput()throws Exception{
  var input=product("admin-test-validation","imprenta-papeleria");input.put("saleType","PACK");call("POST","/products",input,400);
  input.put("packSize",1000);input.put("packLabel","millar");String id=call("POST","/products",input,201).get("id").asText();
  input.put("saleType","AREA");call("PUT","/products/"+id,input,400);
  input.put("packSize",null);input.put("packLabel",null);call("PUT","/products/"+id,input,200);
  input.put("quantityStep",0);call("PUT","/products/"+id,input,400);
  input.put("quantityStep",1);input.put("name"," ");call("PUT","/products/"+id,input,400);
 }
 @Test void materialsAndExtrasStayScopedAndCanBeDisabled()throws Exception{
  String id=createProduct();for(String kind:List.of("materials","extras")){
   String url="/products/"+id+"/"+kind;var input=new HashMap<String,Object>(Map.of("name","Option","active",true,"displayOrder",0));
   String oid=call("POST",url,input,201).get("id").asText();assertThat(call("GET",url,null,200).size()).isEqualTo(1);
   input.put("name","Edited option");input.put("displayOrder",2);call("PUT",url+"/"+oid,input,200);
   assertThat(call("PATCH",url+"/"+oid+"/active",Map.of("active",false),200).get("active").asBoolean()).isFalse();
   call("PUT","/products/banner/"+kind+"/"+oid,input,404);
   call("PATCH",url+"/"+oid+"/active",Map.of("active",true),200);
  }
 }
 @Test void materialCanBeDeletedWithoutDeletingItsProductOrOtherProductsMaterials()throws Exception{
  String productId=createProduct();
  var otherProductInput=product("admin-test-other-product","imprenta-papeleria");
  String otherProductId=call("POST","/products",otherProductInput,201).get("id").asText();
  String materialId=call("POST","/products/"+productId+"/materials",Map.of("name","PVC 5 mm","active",true,"displayOrder",0),201).get("id").asText();
  String otherMaterialId=call("POST","/products/"+otherProductId+"/materials",Map.of("name","Acrilico 2 mm","active",true,"displayOrder",0),201).get("id").asText();

  call("DELETE","/products/"+otherProductId+"/materials/"+materialId,null,404);
  assertThat(jdbc.queryForObject("select count(*) from product_materials where id=?",Integer.class,materialId)).isEqualTo(1);
  call("DELETE","/products/"+productId+"/materials/missing-material",null,404);
  call("DELETE","/products/missing-product/materials/"+materialId,null,404);

  call("DELETE","/products/"+productId+"/materials/"+materialId,null,204);
  assertThat(jdbc.queryForObject("select count(*) from product_materials where id=?",Integer.class,materialId)).isZero();
  assertThat(jdbc.queryForObject("select count(*) from products where id=?",Integer.class,productId)).isEqualTo(1);
  assertThat(jdbc.queryForObject("select count(*) from products where id=?",Integer.class,otherProductId)).isEqualTo(1);
  assertThat(jdbc.queryForObject("select count(*) from product_materials where id=? and product_id=?",Integer.class,otherMaterialId,otherProductId)).isEqualTo(1);
 }
 @Test void materialNamesAreTrimmedAndRequireALetterOrNumberOnCreateAndEdit()throws Exception{
  String productId=createProduct(),url="/products/"+productId+"/materials";
  for(String invalid:List.of("","   ",".",",","-","_"))
   call("POST",url,Map.of("name",invalid,"active",true,"displayOrder",0),400);
  var nullName=new HashMap<String,Object>();nullName.put("name",null);nullName.put("active",true);nullName.put("displayOrder",0);
  call("POST",url,nullName,400);

  List<String> valid=List.of("Banner Grueso (13 Oz)","PVC 5 mm","Acrílico 2 mm","Vinil + PVC","PVC 3 - 5 MM");
  String materialId=null;
  for(int i=0;i<valid.size();i++){
   var response=call("POST",url,Map.of("name","  "+valid.get(i)+"  ","active",true,"displayOrder",i),201);
   assertThat(response.get("name").asText()).isEqualTo(valid.get(i));
   if(materialId==null)materialId=response.get("id").asText();
  }
  for(String invalid:List.of("   ",".",",","-","_"))
   call("PUT",url+"/"+materialId,Map.of("name",invalid,"active",true,"displayOrder",0),400);
  var edited=call("PUT",url+"/"+materialId,Map.of("name","  PVC 3 - 5 MM editado  ","active",true,"displayOrder",0),200);
  assertThat(edited.get("name").asText()).isEqualTo("PVC 3 - 5 MM editado");
 }
 @Test void imagesKeepOnlyOnePrimaryAndValidateReferences()throws Exception{
  String id=createProduct(),url="/products/"+id+"/images";var input=new HashMap<String,Object>(Map.of("url","products/test.webp","altText","Test image","primaryImage",true,"displayOrder",0));
  String first=call("POST",url,input,201).get("id").asText();input.put("url","https://media.example.test/second.webp");
  String second=call("POST",url,input,201).get("id").asText();
  assertThat(call("GET",url,null,200).valueStream().filter(i->i.get("primaryImage").asBoolean()).count()).isEqualTo(1);
  call("PATCH",url+"/"+first+"/primary",Map.of("primaryImage",true),200);
  input.put("primaryImage",false);input.put("altText","Edited alt");call("PUT",url+"/"+second,input,200);
  for(String bad:List.of("javascript:alert(1)","../private","//evil.example/x")){input.put("url",bad);call("POST",url,input,400);}
  call("PATCH","/products/banner/images/"+first+"/primary",Map.of("primaryImage",true),404);
 }
 @Test void repeatedLoginFailuresAreLimited() throws Exception {
  for(int i=0;i<5;i++)json(send("POST","/api/admin/auth/login",Map.of("username","rate-limit-test","password","wrong"),null),401);
  json(send("POST","/api/admin/auth/login",Map.of("username","rate-limit-test","password","wrong"),null),429);
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
