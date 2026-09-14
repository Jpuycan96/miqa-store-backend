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
 private Map<String,Object> category(String slug){return new HashMap<>(Map.of("name","Admin test category","slug",slug,"description","Local test","active",true,"displayOrder",50));}
 private Map<String,Object> product(String slug,String category){var p=new HashMap<String,Object>();p.put("name","Admin test product");p.put("slug",slug);p.put("categoryId",category);p.put("shortDescription","Test");p.put("description","Test detail");p.put("saleType","QUANTITY");p.put("unitLabel","unidad");p.put("minQuantity",1);p.put("quantityStep",1);p.put("published",false);p.put("featured",false);p.put("displayOrder",50);return p;}
 private String createProduct() throws Exception{return call("POST","/products",product("admin-test-product","imprenta-papeleria"),201).get("id").asText();}
 @Test void authProtectsAllAdminResourcesAndLeavesPublicOpen()throws Exception{
  for(String path:List.of("/products","/categories","/auth/me","/products/banner/images","/products/banner/materials","/products/banner/extras"))json(send("GET","/api/admin"+path,null,null),401);
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
  call("GET","/categories/"+id,null,200);input.put("name","Edited category");
  assertThat(call("PUT","/categories/"+id,input,200).get("name").asText()).isEqualTo("Edited category");
  call("POST","/categories",input,409);input.put("slug","INVALID");call("POST","/categories",input,400);
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
 @Test void adminCorsAllowsAuthorizationOnlyForLocalOrigin()throws Exception{
  for(String origin:List.of("http://localhost:4200","https://outside.example")){
   var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/admin/products")).header("Origin",origin).header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","authorization,content-type").method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build();
   var r=client.send(req,HttpResponse.BodyHandlers.ofString());assertThat(r.statusCode()).isEqualTo(origin.startsWith("http://localhost")?200:403);
   if(r.statusCode()==200)assertThat(r.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase()).contains("authorization");
  }
 }
}
