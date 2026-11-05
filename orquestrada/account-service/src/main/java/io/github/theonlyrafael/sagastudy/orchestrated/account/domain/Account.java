package io.github.theonlyrafael.sagastudy.orchestrated.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @Column(name = "account_id", length = 64, nullable = false)
    private String id;

    @Column(name = "available_balance", precision = 19, scale = 2, nullable = false)
    private BigDecimal availableBalance;

    @Column(name = "reserved_balance", precision = 19, scale = 2, nullable = false)
    private BigDecimal reservedBalance;

    @Column(name = "total_consumed", precision = 19, scale = 2, nullable = false)
    private BigDecimal totalConsumed;

    protected Account() {
        // Required by JPA.
    }

    public String getId() {
        return id;
    }

    public BigDecimal getAvailableBalance() {
        return availableBalance;
    }

    public BigDecimal getReservedBalance() {
        return reservedBalance;
    }

    public BigDecimal getTotalConsumed() {
        return totalConsumed;
    }
}
