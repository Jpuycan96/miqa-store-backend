package com.miqa.store.admin;
import com.miqa.store.catalog.TimestampedEntity;
import jakarta.persistence.*;
@Entity @Table(name="admin_users")
public class AdminUser extends TimestampedEntity {
 @Id @Column(length=64) private String id;
 @Column(nullable=false, unique=true, length=100) private String username;
 @Column(nullable=false, length=100) private String passwordHash;
 @Column(nullable=false) private boolean active;
 protected AdminUser() {}
 public AdminUser(String username, String hash) { this.id=java.util.UUID.randomUUID().toString(); this.username=username; this.passwordHash=hash; this.active=true; }
 public String getId(){return id;} public String getUsername(){return username;}
 public String getPasswordHash(){return passwordHash;} public boolean isActive(){return active;}
}
