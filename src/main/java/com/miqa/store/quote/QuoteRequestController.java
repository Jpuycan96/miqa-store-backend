package com.miqa.store.quote;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/quote-requests")
public class QuoteRequestController {
    private final QuoteRequestService service;
    public QuoteRequestController(QuoteRequestService service) { this.service = service; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<QuoteRequestDtos.Confirmation> submit(
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody QuoteRequestDtos.Submission input) {
        var result = service.submit(key, input);
        return ResponseEntity.status(result.replay() ? 200 : 201).header("Cache-Control", "no-store").body(result.confirmation());
    }
}
