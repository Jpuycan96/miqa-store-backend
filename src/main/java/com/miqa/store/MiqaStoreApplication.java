package com.miqa.store;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MiqaStoreApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(MiqaStoreApplication.class, args);
        if (context.getEnvironment().matchesProfiles("admin-bootstrap")) context.close();
    }
}
