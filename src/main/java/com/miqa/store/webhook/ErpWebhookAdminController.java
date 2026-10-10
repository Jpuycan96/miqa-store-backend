package com.miqa.store.webhook;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/** Existing active-admin JWT authentication applies to every route under /api/admin. */
@RestController
@RequestMapping("/api/admin/erp-catalog/webhook-events")
public class ErpWebhookAdminController {
    private final ErpWebhookQueue queue;
    public ErpWebhookAdminController(ErpWebhookQueue queue) { this.queue = queue; }

    public record RequeueInput(@NotNull UUID requestId, @NotBlank @Size(min=8, max=500) String reason) {
        @Override public String toString() { return "RequeueInput[REDACTED]"; }
    }

    @GetMapping("/failed")
    public ResponseEntity<List<ErpWebhookQueue.FailedEvent>> failed(@RequestParam(defaultValue="50") int limit) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(queue.failed(limit));
    }

    @PostMapping("/{eventId}/requeue")
    public ResponseEntity<ErpWebhookQueue.RequeueReceipt> requeue(@PathVariable UUID eventId,
            @Valid @RequestBody RequeueInput input, @AuthenticationPrincipal Jwt admin) {
        return ResponseEntity.accepted().header("Cache-Control", "no-store")
                .body(queue.requeue(eventId, input.requestId(), admin.getSubject(), input.reason()));
    }
}
