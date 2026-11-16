package io.github.theonlyrafael.sagastudy.orchestrated.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record CatalogAsset(String id, String version, BigDecimal unitPrice) {
    public CatalogAsset {
        if (id == null || id.isBlank() || version == null || version.isBlank() || unitPrice == null)
            throw new IllegalArgumentException("Catalog data is required");
        try {
            unitPrice = unitPrice.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("Invalid price precision", error);
        }
        if (unitPrice.signum() <= 0 || unitPrice.precision() > 19)
            throw new IllegalArgumentException("Invalid price");
    }

    public BigDecimal total(int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("Invalid quantity");
        var total = unitPrice.multiply(BigDecimal.valueOf(quantity));
        if (total.precision() > 19)
            throw new IllegalArgumentException("Order total exceeds NUMERIC(19,2)");
        return total;
    }
}
