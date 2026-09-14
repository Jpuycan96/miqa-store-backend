package com.miqa.store.admin;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
@Component @Profile("local")
public class LocalAdminBootstrap implements ApplicationRunner {
 private final AdminUserRepository users; private final PasswordEncoder encoder; private final String username,password;
 public LocalAdminBootstrap(AdminUserRepository users,PasswordEncoder encoder,@Value("${ADMIN_BOOTSTRAP_USERNAME:}") String username,@Value("${ADMIN_BOOTSTRAP_PASSWORD:}") String password){this.users=users;this.encoder=encoder;this.username=username;this.password=password;}
 public void run(ApplicationArguments args){
  if(username.isBlank() && password.isBlank())return;
  if(!username.matches("[a-zA-Z0-9._-]{3,100}") || password.length()<12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
   throw new IllegalStateException("Local bootstrap requires a valid username and password of at least 12 characters / at most 72 UTF-8 bytes");
  if(users.count()==0)users.save(new AdminUser(username,encoder.encode(password)));
 }
}
