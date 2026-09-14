package com.miqa.store.admin;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
/** Local single-instance login throttling; shared/proxy limits are required before multi-instance deployment. */
@Component
public class LoginThrottle {
 private record Attempts(int count,Instant until){}
 private final Map<String,Attempts> attempts=new HashMap<>();
 public synchronized void check(String username){
  Instant now=Instant.now();attempts.entrySet().removeIf(e->!e.getValue().until().isAfter(now));
  var a=attempts.get(username);
  if((a!=null&&a.count()>=5)||(a==null&&attempts.size()>=1024))throw new AdminFailure(429,"Demasiados intentos. Espera un minuto e intenta nuevamente");
 }
 public synchronized void failed(String username){var old=attempts.get(username);attempts.put(username,new Attempts(old==null?1:old.count()+1,old==null?Instant.now().plusSeconds(60):old.until()));}
 public synchronized void success(String username){attempts.remove(username);}
}
