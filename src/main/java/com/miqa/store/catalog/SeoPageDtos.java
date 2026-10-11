package com.miqa.store.catalog;

import java.util.List;

/** Presentation-only contract: no catalog IDs, configuration or administrative data. */
public final class SeoPageDtos {
    private SeoPageDtos() {}

    public enum PageType { PRODUCT, CATEGORY }
    public record Link(String name, String url) {}
    public record Image(String url, String altText) {}
    public record Page(PageType type, String name, String slug, String seoTitle, String seoDescription,
                       String bodyDescription, Image image, List<Link> breadcrumbs, List<Link> links,
                       String canonicalUrl) {}
}
