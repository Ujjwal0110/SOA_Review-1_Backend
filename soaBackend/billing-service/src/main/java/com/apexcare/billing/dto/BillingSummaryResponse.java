package com.apexcare.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Aggregate billing metrics for dashboards.
 *
 * <p>All money fields are {@link BigDecimal} with scale 2. Counts are
 * primitives (never negative; default 0).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BillingSummaryResponse {

    // ---- Amounts ----

    private BigDecimal totalBilled = BigDecimal.ZERO;
    private BigDecimal totalRevenue = BigDecimal.ZERO;
    private BigDecimal pendingAmount = BigDecimal.ZERO;
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    // ---- Counts ----

    private long totalBills;
    private long pendingBills;
    private long paidBills;
    private long failedBills;
    private long refundedBills;

    // ---- Computed (derived, no setter needed) ----

    /** Collection rate = totalRevenue / totalBilled × 100. Null-safe: 0 if totalBilled is 0. */
    public BigDecimal getCollectionRatePercent() {
        if (totalBilled == null || totalBilled.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return totalRevenue
                .multiply(BigDecimal.valueOf(100))
                .divide(totalBilled, 2, RoundingMode.HALF_UP);
    }

    // =================================================================
    // Getters / setters
    // =================================================================

    public BigDecimal getTotalBilled() { return totalBilled; }
    public void setTotalBilled(BigDecimal totalBilled) {
        this.totalBilled = totalBilled != null ? totalBilled : BigDecimal.ZERO;
    }

    public BigDecimal getTotalRevenue() { return totalRevenue; }
    public void setTotalRevenue(BigDecimal totalRevenue) {
        this.totalRevenue = totalRevenue != null ? totalRevenue : BigDecimal.ZERO;
    }

    public BigDecimal getPendingAmount() { return pendingAmount; }
    public void setPendingAmount(BigDecimal pendingAmount) {
        this.pendingAmount = pendingAmount != null ? pendingAmount : BigDecimal.ZERO;
    }

    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public void setRefundedAmount(BigDecimal refundedAmount) {
        this.refundedAmount = refundedAmount != null ? refundedAmount : BigDecimal.ZERO;
    }

    public long getTotalBills() { return totalBills; }
    public void setTotalBills(long totalBills) { this.totalBills = totalBills; }

    public long getPendingBills() { return pendingBills; }
    public void setPendingBills(long pendingBills) { this.pendingBills = pendingBills; }

    public long getPaidBills() { return paidBills; }
    public void setPaidBills(long paidBills) { this.paidBills = paidBills; }

    public long getFailedBills() { return failedBills; }
    public void setFailedBills(long failedBills) { this.failedBills = failedBills; }

    public long getRefundedBills() { return refundedBills; }
    public void setRefundedBills(long refundedBills) { this.refundedBills = refundedBills; }
}