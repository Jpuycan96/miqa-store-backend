package com.miqa.store.pricing;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class PricingController {
    public static final String PATH = "/api/public/pricing/evaluate";
    private final PricingService service;
    private final tools.jackson.databind.ObjectMapper mapper;
    public PricingController(PricingService service, tools.jackson.databind.ObjectMapper mapper) { this.service=service; this.mapper=mapper; }
    @PostMapping(value=PATH,consumes="application/json",produces="application/json")
    public ResponseEntity<PricingDtos.PublicResult> evaluate(@RequestBody java.util.Map<String,Object> body) {
        if (!java.util.Set.of("productId","quantity","erpMaterialId","erpModelId","measures").containsAll(body.keySet()))
            throw new PricingFailure(PricingDtos.Status.CONFIGURATION_INVALID);
        PricingDtos.Input input;
        try { input = mapper.convertValue(body,PricingDtos.Input.class); }
        catch (IllegalArgumentException ex) { throw new PricingFailure(PricingDtos.Status.CONFIGURATION_INVALID); }
        var result = service.evaluate(input);
        return ResponseEntity.status(result.status().httpStatus()).header("Cache-Control","no-store").body(result);
    }
}
