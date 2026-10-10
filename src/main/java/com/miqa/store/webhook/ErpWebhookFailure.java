package com.miqa.store.webhook;

final class ErpWebhookFailure extends RuntimeException {
    private final int status;
    private final String code;
    ErpWebhookFailure(int status, String code) { super(code); this.status = status; this.code = code; }
    int status() { return status; }
    String code() { return code; }
}
