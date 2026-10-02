package com.miqa.store.export;

final class RequestExportFailure extends RuntimeException {
    final int status;
    final String code;
    RequestExportFailure(int status, String code) { super("No se pudo consultar la solicitud"); this.status=status; this.code=code; }
    static RequestExportFailure invalid() { return new RequestExportFailure(400,"INVALID_EXPORT_QUERY"); }
}
