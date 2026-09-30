package com.miqa.store.erp;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.miqa.store.erp.ErpCatalogDtos.*;

@RestController
@RequestMapping("/api/admin/erp-catalog")
public class ErpCatalogController {
    private final ErpCatalogService service;
    public ErpCatalogController(ErpCatalogService service) { this.service = service; }

    @PostMapping("/sync")
    public ResponseEntity<SyncStatus> synchronize() {
        var result = service.synchronize();
        int status = switch (result.outcome()) { case "SUCCESS" -> 200; case "NOT_CONFIGURED" -> 503; default -> 502; };
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(result);
    }
    @GetMapping("/sync") public SyncStatus status() { return service.status(); }
    @GetMapping("/services") public List<Projection> services() { return service.projections(); }
    @GetMapping("/bindings/{productId}")
    public ProductErpBinding binding(@PathVariable String productId) { return service.binding(productId); }
    @PutMapping("/bindings/{productId}")
    public ProductErpBinding bind(@PathVariable String productId, @Valid @RequestBody BindingInput input) {
        return service.bind(productId, input);
    }
}
