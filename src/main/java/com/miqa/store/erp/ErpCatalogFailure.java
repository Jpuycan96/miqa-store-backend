package com.miqa.store.erp;

/** Deliberately carries neither remote response nor request/exception cause. */
final class ErpCatalogFailure extends RuntimeException {
    private final String code;
    ErpCatalogFailure(String code) { super(code); this.code = code; }
    String code() { return code; }
}
