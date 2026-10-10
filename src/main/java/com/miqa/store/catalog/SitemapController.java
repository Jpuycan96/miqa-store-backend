package com.miqa.store.catalog;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SitemapController {
    public static final String PATH = "/api/public/seo/sitemap.xml";
    private static final Logger log = LoggerFactory.getLogger(SitemapController.class);
    private final SitemapService sitemap;

    public SitemapController(SitemapService sitemap) { this.sitemap = sitemap; }

    @GetMapping(value = PATH, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(new MediaType("application", "xml", StandardCharsets.UTF_8))
                .body(sitemap.xml());
    }

    // An XML-only client must also receive a safe error status without JSON negotiation.
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Void> failed(RuntimeException ex) {
        log.error("Sitemap request failure; exception type: {}", ex.getClass().getName());
        return ResponseEntity.internalServerError().cacheControl(CacheControl.noStore()).build();
    }
}
