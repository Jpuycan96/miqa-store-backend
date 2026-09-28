package com.miqa.store.quote;

public class QuoteRequestFailure extends RuntimeException {
    private final int status;
    public QuoteRequestFailure(int status, String message) {
        super(message);
        this.status = status;
    }
    public int status() { return status; }
    public static QuoteRequestFailure invalid() {
        return new QuoteRequestFailure(400, "Revisa el formato y la configuración de la solicitud");
    }
    public static QuoteRequestFailure catalogChanged() {
        return new QuoteRequestFailure(409, "El catálogo cambió; revisa los productos y opciones seleccionados");
    }
}
