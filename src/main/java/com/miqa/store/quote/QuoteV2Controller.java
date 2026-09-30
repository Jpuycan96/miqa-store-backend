package com.miqa.store.quote;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class QuoteV2Controller {
    private final QuoteV2Service service;
    public QuoteV2Controller(QuoteV2Service service) { this.service=service; }
    @PostMapping(value="/api/public/quote-requests/v2", consumes="application/json", produces="application/json")
    public ResponseEntity<QuoteRequestDtos.Confirmation> submit(@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody QuoteV2Dtos.Submission input) {
        var result = service.submit(key, input);
        return ResponseEntity.status(result.replay() ? 200 : 201).header("Cache-Control", "no-store").body(result.confirmation());
    }
}
