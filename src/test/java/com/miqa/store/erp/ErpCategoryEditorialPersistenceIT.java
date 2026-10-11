package com.miqa.store.erp;

import com.miqa.store.admin.*;
import com.miqa.store.catalog.*;
import com.miqa.store.config.MediaProperties;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.math.BigInteger;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in, no Boot/component scan/Flyway/HTTP/ERP client. See adjacent recovery documentation. */
@EnabledIfEnvironmentVariable(named="RUN_ERP_CATEGORY_EDITORIAL_IT", matches="true")
class ErpCategoryEditorialPersistenceIT {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private boolean fixtureOwned;
    private final String categoryId = UUID.randomUUID().toString();
    private final String slug = "category-editorial-it-" + categoryId;
    private final String erpCategoryId = numericId();
    private final String erpServiceId = numericId();
    private String productId;

    private static String numericId() { return new BigInteger(UUID.randomUUID().toString().replace("-", ""),16).add(BigInteger.ONE).toString(); }

    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses=CategoryRepository.class)
    static class Config {
        @Bean DataSource dataSource() throws SQLException {
            String password=System.getenv("TEST_DB_PASSWORD");
            if(password==null || password.isBlank()) throw new IllegalStateException("TEST_DB_PASSWORD required");
            var source=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55432/miqa_store_test_db", "miqa_store_local", password) {
                @Override public Connection getConnection() throws SQLException {
                    var connection=super.getConnection();
                    try { connection.setSchema("public"); return connection; }
                    catch(SQLException failure) { connection.close(); throw failure; }
                }
            };
            // All checks precede EntityManagerFactory creation and any fixture write.
            try(var c=source.getConnection(); var s=c.createStatement()) {
                c.setReadOnly(true);
                try(var r=s.executeQuery("SELECT current_database(),current_user,session_user,host(inet_server_addr()),inet_server_port(),current_setting('data_directory')")) {
                    r.next();
                    if(!"miqa_store_test_db".equals(r.getString(1)) || !"miqa_store_local".equals(r.getString(2))
                        || !"miqa_store_local".equals(r.getString(3)) || !"127.0.0.1".equals(r.getString(4)) || r.getInt(5)!=55432
                        || !"d:/miqa-store/miqa-store-backend/.local/postgres-test/data".equals(r.getString(6).replace('\\','/').toLowerCase(Locale.ROOT)))
                        throw new IllegalStateException("TEST isolation check failed; no writes permitted");
                }
                // Parse/type-check every column used by the fixture/reconciliation without retrieving rows.
                for(String sql:List.of(
                    "SELECT id,erp_category_id,name,slug,description,catalog_headline,catalog_description,active,display_order FROM public.categories WHERE false",
                    "SELECT id,category_id,name,slug,short_description,description,catalog_mode,featured,published,display_order FROM public.products WHERE false",
                    "SELECT product_id,erp_service_id,active,canonical FROM public.product_erp_bindings WHERE false",
                    "SELECT erp_service_id,catalog_revision,payload,sync_state,last_synced_at FROM public.erp_catalog_services WHERE false",
                    "SELECT slug FROM public.category_slug_aliases WHERE false")) s.executeQuery(sql).close();
                try(var r=s.executeQuery("SELECT count(*) FROM public.flyway_schema_history WHERE success AND version IN ('5','8','10')")) {
                    r.next(); if(r.getInt(1)!=3) throw new IllegalStateException("Required TEST migrations must already exist");
                }
                try(var r=s.executeQuery("SELECT count(*) FROM pg_index WHERE indexrelid IN ('public.uq_categories_erp_identity'::regclass,'public.uq_product_erp_canonical'::regclass) AND indisunique AND indisvalid AND indisready")) {
                    r.next(); if(r.getInt(1)!=2) throw new IllegalStateException("Required TEST identity indexes missing");
                }
            }
            return source;
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory=new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source); factory.setPackagesToScan("com.miqa.store.catalog");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","validate",
                "jakarta.persistence.schema-generation.database.action","none",
                "hibernate.physical_naming_strategy",SnakeCase.class.getName()));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory factory, DataSource source) {
            var manager=new JpaTransactionManager(factory); manager.setDataSource(source); return manager;
        }
        @Bean EntityManager entityManager(jakarta.persistence.EntityManagerFactory factory) { return SharedEntityManagerCreator.createSharedEntityManager(factory); }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean AdminCatalogService admin(CategoryRepository categories, CategorySlugAliasRepository aliases, ProductRepository products, @org.springframework.beans.factory.annotation.Qualifier("entityManager") EntityManager em) {
            return new AdminCatalogService(categories,aliases,products,em,new MediaProperties(),org.mockito.Mockito.mock(ProductImageStorage.class));
        }
        @Bean ErpCatalogRepository erp(JdbcTemplate jdbc) { return new ErpCatalogRepository(jdbc,JsonMapper.builder().build()); }
    }
    public static class SnakeCase extends org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl {
        @Override public org.hibernate.boot.model.naming.Identifier toPhysicalColumnName(org.hibernate.boot.model.naming.Identifier name, org.hibernate.engine.jdbc.env.spi.JdbcEnvironment env) {
            return org.hibernate.boot.model.naming.Identifier.toIdentifier(name.getText().replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase(Locale.ROOT),name.isQuoted());
        }
    }
    @BeforeEach void setup() {
        context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("explicit-no-migrations",Map.of("spring.flyway.enabled","false","spring.sql.init.mode","never")));
        context.register(Config.class); context.refresh();
        jdbc=context.getBean(JdbcTemplate.class); tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        System.out.println("ERP_CATEGORY_EDITORIAL_IT fixtures: category="+categoryId+" erpCategory="+erpCategoryId+" erpService="+erpServiceId);
        tx.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE id=? OR slug=? OR erp_category_id=?",Integer.class,categoryId,slug,erpCategoryId)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM erp_catalog_services WHERE erp_service_id=?",Integer.class,erpServiceId)).isZero();
            jdbc.update("INSERT INTO categories(id,name,slug,erp_category_id) VALUES (?,'Synthetic ERP category',?,?)",categoryId,slug,erpCategoryId);
        });
        fixtureOwned=true;
    }
    private ErpCatalogContract contract() {
        return new ErpCatalogContract(1,"ERP_GIGANTOGRAFIAS",erpServiceId,"Synthetic service",
            new ErpCatalogContract.Category(erpCategoryId,"Synthetic ERP category"),true,true,"CONFIGURADA",List.of(),"1","a".repeat(64),Instant.now(),null);
    }
    @Test void committedEditorialSurvivesRepeatedLocalReconciliation() {
        var admin=context.getBean(AdminCatalogService.class);
        for(String field:List.of("id","slug","erpCategoryId","erp_category_id")) {
            assertThatThrownBy(() -> JsonMapper.builder().build().readValue(
                "{\"name\":\"Synthetic ERP category\",\"active\":true,\"displayOrder\":0,\""+field+"\":\"tampered\"}",AdminDtos.CategoryInput.class))
                .isInstanceOf(RuntimeException.class);
        }
        admin.saveCategory(categoryId,new AdminDtos.CategoryInput("Synthetic ERP category","Synthetic description","Synthetic headline","Synthetic header description",false,37));
        assertEditorial(); // JdbcTemplate outside the service transaction opens an independent connection.
        assertThatThrownBy(() -> admin.saveCategory(categoryId,new AdminDtos.CategoryInput("Unauthorized rename","bad","bad","bad",true,0))).isInstanceOf(AdminFailure.class);
        assertEditorial();
        var erp=context.getBean(ErpCatalogRepository.class);
        for(int i=0;i<2;i++) {
            tx.executeWithoutResult(status -> { erp.upsert(contract(),Instant.now()); erp.reconcile(contract()); });
            assertEditorial();
            String canonical=jdbc.queryForObject("SELECT product_id FROM product_erp_bindings WHERE erp_service_id=? AND canonical",String.class,erpServiceId);
            if(productId==null) productId=canonical; else assertThat(canonical).isEqualTo(productId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE category_id=?",Integer.class,categoryId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE erp_category_id=?",Integer.class,erpCategoryId)).isEqualTo(1);
        }
    }
    private void assertEditorial() {
        var row=jdbc.queryForMap("SELECT id,name,slug,erp_category_id,description,catalog_headline,catalog_description,active,display_order FROM categories WHERE id=?",categoryId);
        assertThat(row).containsEntry("id",categoryId).containsEntry("name","Synthetic ERP category").containsEntry("slug",slug)
            .containsEntry("erp_category_id",erpCategoryId).containsEntry("description","Synthetic description")
            .containsEntry("catalog_headline","Synthetic headline").containsEntry("catalog_description","Synthetic header description")
            .containsEntry("active",false).containsEntry("display_order",37);
    }
    @AfterEach void cleanup() {
        try {
            if(fixtureOwned) {
                tx.executeWithoutResult(status -> {
                    // Exact fixture identity only; never broad prefix deletion or real catalog adoption.
                    var ids=jdbc.queryForList("SELECT p.id FROM products p JOIN product_erp_bindings b ON b.product_id=p.id WHERE b.erp_service_id=? AND p.category_id=?",String.class,erpServiceId,categoryId);
                    for(String id:ids) {
                        jdbc.update("DELETE FROM product_erp_bindings WHERE product_id=? AND erp_service_id=?",id,erpServiceId);
                        jdbc.update("DELETE FROM products WHERE id=? AND category_id=?",id,categoryId);
                    }
                    jdbc.update("DELETE FROM erp_catalog_services WHERE erp_service_id=?",erpServiceId);
                    jdbc.update("DELETE FROM categories WHERE id=? AND erp_category_id=? AND slug=?",categoryId,erpCategoryId,slug);
                });
                assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE id=? OR erp_category_id=?",Integer.class,categoryId,erpCategoryId)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE category_id=?",Integer.class,categoryId)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM product_erp_bindings WHERE erp_service_id=?",Integer.class,erpServiceId)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM erp_catalog_services WHERE erp_service_id=?",Integer.class,erpServiceId)).isZero();
            }
        } finally { if(context!=null) context.close(); }
    }
}
