package com.miqa.store.pricing;

public class PricingFailure extends RuntimeException {
    private final PricingDtos.Status status;
    public PricingFailure(PricingDtos.Status status) { super(status.name()); this.status=status; }
    public PricingDtos.Status status() { return status; }
}
