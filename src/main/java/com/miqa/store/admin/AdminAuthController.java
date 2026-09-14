package com.miqa.store.admin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/admin/auth")
public class AdminAuthController {
 public record Login(@NotBlank @Size(max=100) String username,@NotBlank @Size(max=72) String password){ @Override public String toString(){return "Login[credentials=REDACTED]";} }
 public record Session(String token,Instant expiresAt){ @Override public String toString(){return "Session[token=REDACTED]";} }
 public record Me(String id,String username){}
 private final AdminUserRepository users;private final PasswordEncoder passwords;private final JwtEncoder encoder;private final Duration expiration;private final String dummyHash;private final LoginThrottle throttle;
 public AdminAuthController(AdminUserRepository users,PasswordEncoder passwords,JwtEncoder encoder,LoginThrottle throttle,@Value("${app.admin.jwt-expiration}") Duration expiration){
  this.throttle=throttle;this.users=users;this.passwords=passwords;this.encoder=encoder;this.expiration=expiration;this.dummyHash=passwords.encode(UUID.randomUUID().toString());
  if(expiration.compareTo(Duration.ofMinutes(1))<0 || expiration.compareTo(Duration.ofHours(24))>0)throw new IllegalStateException("JWT expiration must be between 1 minute and 24 hours");
 }
 @PostMapping("/login") public Session login(@Valid @RequestBody Login request){
  throttle.check(request.username());
  var user=users.findByUsername(request.username()).orElse(null);
  boolean valid=request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=72 && passwords.matches(request.password(),user==null?dummyHash:user.getPasswordHash());
  if(!valid || user==null || !user.isActive()){throttle.failed(request.username());throw new AdminFailure(401,"Usuario o contrasena incorrectos");}
  throttle.success(request.username());
  Instant now=Instant.now(),end=now.plus(expiration);
  var claims=JwtClaimsSet.builder().issuer("miqa-store-admin").audience(List.of("miqa-store-admin-api")).subject(user.getId()).issuedAt(now).expiresAt(end).id(UUID.randomUUID().toString()).build();
  return new Session(encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue(),end);
 }
 @GetMapping("/me") public Me me(@AuthenticationPrincipal Jwt jwt){var user=users.findById(jwt.getSubject()).orElseThrow(()->new AdminFailure(401,"Sesion no disponible"));return new Me(user.getId(),user.getUsername());}
}
