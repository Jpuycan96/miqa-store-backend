package com.miqa.store.catalog;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

@Service
public class SitemapService {
    private static final String ORIGIN = "https://store.solucionesmicaela.com";
    private static final String NAMESPACE = "http://www.sitemaps.org/schemas/sitemap/0.9";
    private final CatalogService catalog;

    public SitemapService(CatalogService catalog) { this.catalog = catalog; }

    // Both public queries share one snapshot, including the ERP eligibility projection.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String xml() {
        var locations = new LinkedHashSet<String>();
        locations.add(ORIGIN + "/");
        locations.add(ORIGIN + "/productos");
        catalog.categories().forEach(category -> locations.add(catalogUrl(category.slug())));
        catalog.products(null, null, null).forEach(product -> locations.add(catalogUrl(product.slug())));

        // Build the whole response before sending HTTP 200; query/render failures propagate.
        var output = new StringWriter();
        try {
            var writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            writer.writeStartElement("urlset");
            writer.writeDefaultNamespace(NAMESPACE);
            for (String location : locations) {
                writer.writeStartElement("url");
                writer.writeStartElement("loc");
                writer.writeCharacters(location);
                writer.writeEndElement();
                writer.writeEndElement();
            }
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.close();
            return output.toString();
        } catch (XMLStreamException ex) {
            throw new IllegalStateException("Could not render sitemap", ex);
        }
    }

    private String catalogUrl(String slug) {
        if (slug == null || slug.isBlank()) throw new IllegalStateException("Missing public catalog slug");
        return ORIGIN + "/productos/" + UriUtils.encodePathSegment(slug, StandardCharsets.UTF_8);
    }
}
