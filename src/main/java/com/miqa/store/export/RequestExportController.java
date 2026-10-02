package com.miqa.store.export;

import com.miqa.store.error.ApiError;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping(RequestExportController.PATH)
public class RequestExportController {
    public static final String PATH="/api/integracion/erp/v1/solicitudes";
    private final RequestExportService service;
    public RequestExportController(RequestExportService service) { this.service=service; }
    @GetMapping
    public RequestExportDtos.Page list(@RequestParam(required=false) String cursor,
            @RequestParam(required=false) String createdFrom, @RequestParam(required=false) String createdBefore,
            @RequestParam(required=false) Integer limit) {
        return service.list(cursor,createdFrom,createdBefore,limit);
    }
    @GetMapping("/{id}")
    public RequestExportDtos.Detail detail(@PathVariable String id) { return service.detail(id); }
    @ExceptionHandler(RequestExportFailure.class)
    public ResponseEntity<ApiError> failure(RequestExportFailure ex) {
        return ResponseEntity.status(ex.status).header("Cache-Control","no-store").body(new ApiError(
                Instant.now(),ex.status,ex.code,ex.getMessage(),PATH,Map.of()));
    }
}
