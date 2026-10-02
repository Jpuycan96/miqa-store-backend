package com.miqa.store.export;

import com.miqa.store.quote.QuoteSnapshot;
import com.miqa.store.quote.QuoteV2Dtos;
import java.time.Instant;
import java.util.List;

public final class RequestExportDtos {
    private RequestExportDtos() {}
    public record Summary(String id, String reference, String origin, String status, Instant createdAt, Instant updatedAt) {}
    public record Contact(String name, String phone, String email) {
        @Override public String toString() { return "Contact[REDACTED]"; }
    }
    public record Item(String id, int position, int snapshotVersion, String type,
                       QuoteSnapshot legacy, QuoteV2Dtos.ErpSnapshot erp) {
        @Override public String toString() { return "ExportItem[REDACTED]"; }
    }
    public record Detail(int contractVersion, String sourceSystem, String id, String reference,
            String origin, String status, Instant createdAt, Instant updatedAt,
            Contact contact, String notes, List<Item> items) {
        @Override public String toString() { return "RequestExport[REDACTED]"; }
    }
    public record Window(Instant createdFrom, Instant createdBefore) {}
    public record Page(int contractVersion, String sourceSystem, Window window, List<Summary> requests,
                       String nextCursor, boolean hasMore) {}
}
