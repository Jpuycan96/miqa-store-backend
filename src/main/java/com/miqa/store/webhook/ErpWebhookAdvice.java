package com.miqa.store.webhook;

import com.miqa.store.error.ApiError;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;

@RestControllerAdvice(assignableTypes = ErpWebhookController.class)
@org.springframework.core.annotation.Order(-1)
public class ErpWebhookAdvice {
    @ExceptionHandler(ErpWebhookFailure.class)
    ResponseEntity<ApiError> invalid(ErpWebhookFailure failure) { return error(failure.status(), failure.code()); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> unavailable() { return error(503, "RECEIPT_UNAVAILABLE"); }
    @ExceptionHandler(org.springframework.transaction.TransactionException.class)
    ResponseEntity<ApiError> commitUnavailable() { return error(503, "RECEIPT_UNAVAILABLE"); }
    private ResponseEntity<ApiError> error(int status, String code) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(new ApiError(Instant.now(),status,
                code,"Webhook no aceptado",ErpWebhookController.PATH,Map.of()));
    }
}
