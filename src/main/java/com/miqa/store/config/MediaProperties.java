package com.miqa.store.config;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** References are stored in PostgreSQL; storage is reserved for the future VPS upload service. */
@Component
@ConfigurationProperties(prefix = "app.media")
public class MediaProperties {
    private Path storagePath;
    private String baseUrl = "";
    public Path getStoragePath() { return storagePath; }
    public void setStoragePath(Path storagePath) { this.storagePath = storagePath; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (!value.isEmpty()) {
            URI uri = URI.create(value);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Media base URL must be an HTTP(S) origin/path without credentials or query");
            }
        }
        this.baseUrl = value;
    }
    public String publicUrl(String reference) {
        if (reference == null || reference.isBlank()) return "";
        URI uri = URI.create(reference);
        if (uri.isAbsolute()) {
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("Unsupported image URL");
            }
            return reference;
        }
        if (reference.startsWith("//") || uri.getPath().contains("..") || reference.contains("\\")) {
            throw new IllegalArgumentException("Invalid media reference");
        }
        return baseUrl.isEmpty() ? reference : baseUrl + "/" + reference.replaceFirst("^/+", "");
    }
}
