package com.miqa.store.webhook;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ErpWebhookController {
    public static final String PATH = "/api/integracion/erp/v1/catalogo/eventos";
    private final ErpWebhookReceiver receiver;
    public ErpWebhookController(ErpWebhookReceiver receiver) { this.receiver = receiver; }
    @PostMapping(PATH)
    public ResponseEntity<ErpWebhookReceiver.Receipt> receive(HttpServletRequest request) {
        var receipt = receiver.receive((byte[]) request.getAttribute(ErpWebhookAuthenticationFilter.BODY));
        return ResponseEntity.status("ACCEPTED".equals(receipt.status()) ? 202 : 200)
                .header("Cache-Control", "no-store").body(receipt);
    }
}
