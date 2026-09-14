package com.miqa.store.admin;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface AdminUserRepository extends JpaRepository<AdminUser,String> {
 Optional<AdminUser> findByUsername(String username);
 boolean existsByIdAndActiveTrue(String id);
}
