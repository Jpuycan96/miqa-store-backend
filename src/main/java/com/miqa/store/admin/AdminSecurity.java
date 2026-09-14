package com.miqa.store.admin;
import com.miqa.store.error.ApiError;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;
@Configuration
public class AdminSecurity {
 @Bean PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder(12);}
 @Bean SecretKeySpec adminKey(@Value("${app.admin.jwt-secret}") String secret){
  byte[] bytes; try { bytes=Base64.getDecoder().decode(secret); }catch(IllegalArgumentException ex){throw new IllegalStateException("ADMIN_JWT_SECRET must be base64 encoded");}
  if(bytes.length<32)throw new IllegalStateException("ADMIN_JWT_SECRET needs at least 32 random bytes");
  return new SecretKeySpec(bytes,"HmacSHA256");
 }
 @Bean JwtEncoder jwtEncoder(SecretKeySpec key){return new NimbusJwtEncoder(new ImmutableSecret<>(key));}
 @Bean JwtDecoder jwtDecoder(SecretKeySpec key, AdminUserRepository users){
  var decoder=NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
  decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer("miqa-store-admin"), jwt ->
   jwt.getAudience().contains("miqa-store-admin-api") && jwt.getSubject()!=null && users.existsByIdAndActiveTrue(jwt.getSubject())
    ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
  return decoder;
 }
 @Bean SecurityFilterChain security(HttpSecurity http,ObjectMapper mapper) throws Exception {
  // Header-only bearer tokens; no cookie authentication or server session.
  return http.csrf(csrf->csrf.disable()).cors(cors->{})
   .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a->a.requestMatchers(HttpMethod.POST,"/api/admin/auth/login").permitAll()
    .requestMatchers("/api/admin/**").authenticated().requestMatchers("/api/public/**","/error").permitAll().anyRequest().denyAll())
   .exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->error(mapper,q,r,401)).accessDeniedHandler((q,r,x)->error(mapper,q,r,403)))
   .oauth2ResourceServer(o->o.jwt(j->{}).authenticationEntryPoint((q,r,x)->error(mapper,q,r,401)))
   .build();
 }
 private void error(ObjectMapper mapper,HttpServletRequest req,HttpServletResponse res,int status) throws java.io.IOException {
  res.setStatus(status);res.setContentType("application/json");res.setCharacterEncoding("UTF-8");
  res.getWriter().write(mapper.writeValueAsString(new ApiError(Instant.now(),status,status==401?"UNAUTHENTICATED":"FORBIDDEN",status==401?"Inicia sesion para continuar":"Acceso no permitido",req.getRequestURI(),Map.of())));
 }
}
