package com.miqa.store.catalog;

import com.miqa.store.error.ApiError;
import com.miqa.store.error.CatalogNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class SeoPageController {
    public static final String PATH = "/api/public/seo/pages/{slug}";
    private static final Logger log = LoggerFactory.getLogger(SeoPageController.class);
    private final SeoPageService pages;

    public SeoPageController(SeoPageService pages) { this.pages = pages; }

    @GetMapping(value = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> page(@PathVariable String slug, HttpServletRequest request) {
        var result = pages.resolve(slug);
        if (result instanceof SeoPageService.Redirect redirect)
            return ResponseEntity.status(301).location(redirect.location()).cacheControl(CacheControl.noStore()).build();
        if ("HEAD".equals(request.getMethod())) return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(((SeoPageService.Found) result).page());
    }

    @ExceptionHandler(CatalogNotFoundException.class)
    public ResponseEntity<ApiError> notFound(HttpServletRequest request) {
        return error(404, "NOT_FOUND", "Recurso no disponible", request);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiError> unexpected(RuntimeException exception, HttpServletRequest request) {
        log.error("SEO page resolution failed; exception type: {}", exception.getClass().getName());
        return error(500, "INTERNAL_ERROR", "No se pudo completar la solicitud", request);
    }

    private static ResponseEntity<ApiError> error(int status, String code, String message, HttpServletRequest request) {
        if ("HEAD".equals(request.getMethod())) return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).build();
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError(Instant.now(), status, code, message, request.getRequestURI(), Map.of()));
    }
}
