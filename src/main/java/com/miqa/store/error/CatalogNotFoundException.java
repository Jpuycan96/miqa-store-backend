package com.miqa.store.error;

public class CatalogNotFoundException extends RuntimeException {
    public CatalogNotFoundException(String message) { super(message); }
}
