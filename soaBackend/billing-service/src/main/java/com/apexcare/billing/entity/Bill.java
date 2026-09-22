package com.apexcare.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "bills",
        indexes = {
                @Index(name = "idx_bills_patient_id", columnList = "patient_id"),
                @Index(name = "idx_bills_appointment_id", columnList = "appointment_id"),
                @Index(name = "idx_bills_payment_status", columnList = "payment_status"),
                @Index(name = "idx_bills_created_at", columnList = "created_at"),
                @Index(name = "idx_bills_paid_at", columnList = "paid_at"),
                @Index(name = "idx_bills_patient_status", columnList = "patient_id,payment_status")
        }
)
public class Bill {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    // ---- Patient snapshot ----

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "unique_patient_id", length = 32, updatable = false)
    private String uniquePatientId;

    @Column(name = "appointment_id", updatable = false)
    private UUID appointmentId;

    // ---- Line items (inputs) ----

    @Column(name = "consultation_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal consultationFee = BigDecimal.ZERO;

    @Column(name = "medicine_charges", nullable = false, precision = 12, scale = 2)
    private BigDecimal medicineCharges = BigDecimal.ZERO;

    @Column(name = "lab_charges", nullable = false, precision = 12, scale = 2)
    private BigDecimal labCharges = BigDecimal.ZERO;

    @Column(name = "other_charges", nullable = false, precision = 12, scale = 2)
    private BigDecimal otherCharges = BigDecimal.ZERO;

    // ---- Computed totals (managed by lifecycle, no public setters) ----

    @Column(name = "subtotal_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal subtotalAmount = BigDecimal.ZERO;

    @Column(name = "discount_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercentage = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "tax_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal taxPercentage = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    // ---- Payment ----

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 32)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 32)
    private PaymentMethod paymentMethod;

    @Column(name = "transaction_reference", length = 80)
    private String transactionReference;

    @Column(length = 500)
    private String notes;

    // ---- Audit ----

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "paid_at")
    private Instant paidAt;

    private UUID createdBy;

    @Version
    private Long version;

    // =================================================================
    // Lifecycle
    // =================================================================

    @PrePersist
    void onCreate() {
        this.id = UUID.randomUUID();
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.paymentStatus == null) {
            this.paymentStatus = PaymentStatus.PENDING;
        }
        validateInputs();
        recalculateTotals();
        validateInvariants();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
        validateInputs();
        recalculateTotals();
        validateInvariants();
    }

    // =================================================================
    // Domain logic
    // =================================================================

    private void recalculateTotals() {
        BigDecimal fee   = nz(consultationFee);
        BigDecimal med   = nz(medicineCharges);
        BigDecimal lab   = nz(labCharges);
        BigDecimal other = nz(otherCharges);

        this.subtotalAmount = fee.add(med).add(lab).add(other)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal discPct = nz(discountPercentage);
        this.discountAmount = subtotalAmount
                .multiply(discPct)
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);

        BigDecimal taxable = subtotalAmount.subtract(discountAmount);
        if (taxable.compareTo(BigDecimal.ZERO) < 0) {
            taxable = BigDecimal.ZERO;
        }

        BigDecimal taxPct = nz(taxPercentage);
        this.taxAmount = taxable
                .multiply(taxPct)
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);

        this.totalAmount = taxable.add(taxAmount).setScale(2, RoundingMode.HALF_UP);
    }

    private void validateInputs() {
        requireNonNegative(consultationFee, "consultationFee");
        requireNonNegative(medicineCharges, "medicineCharges");
        requireNonNegative(labCharges, "labCharges");
        requireNonNegative(otherCharges, "otherCharges");
        requirePercentage(discountPercentage, "discountPercentage");
        requirePercentage(taxPercentage, "taxPercentage");
    }

    private void validateInvariants() {
        if (this.paymentStatus == PaymentStatus.PAID) {
            if (this.paidAt == null) {
                throw new IllegalStateException(
                        "PAID bill must have paidAt timestamp");
            }
            if (this.paymentMethod == null) {
                throw new IllegalStateException(
                        "PAID bill must have a payment method");
            }
        } else {
            if (this.paidAt != null) {
                throw new IllegalStateException(
                        "paidAt can only be set when paymentStatus is PAID");
            }
        }
    }

    // ---- Payment state transitions ----

    /** Marks the bill as paid. Idempotent-safe. */
    public void markPaid(PaymentMethod method, String transactionReference) {
        if (this.paymentStatus == PaymentStatus.PAID) {
            return;   // idempotent
        }
        if (this.paymentStatus != PaymentStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot mark a " + paymentStatus + " bill as paid");
        }
        if (method == null) {
            throw new IllegalArgumentException("paymentMethod is required");
        }

        this.paymentStatus = PaymentStatus.PAID;
        this.paymentMethod = method;
        this.transactionReference = transactionReference;
        this.paidAt = Instant.now();
    }

    public void markRefunded() {
        if (this.paymentStatus != PaymentStatus.PAID) {
            throw new IllegalStateException("Only PAID bills can be refunded");
        }
        this.paymentStatus = PaymentStatus.REFUNDED;
        this.paidAt = null;   // clear so invariant passes
    }

    // =================================================================
    // equals / hashCode
    // =================================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Bill)) return false;
        Bill other = (Bill) o;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    // =================================================================
    // Helpers
    // =================================================================

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static void requireNonNegative(BigDecimal v, String field) {
        if (v != null && v.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
    }

    private static void requirePercentage(BigDecimal v, String field) {
        if (v == null) return;
        if (v.compareTo(BigDecimal.ZERO) < 0
                || v.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException(
                    field + " must be between 0 and 100 (got " + v + ")");
        }
    }

    // =================================================================
    // Getters / setters
    // =================================================================

    public UUID getId() { return id; }

    public UUID getPatientId() { return patientId; }
    public void setPatientId(UUID patientId) { this.patientId = patientId; }

    public String getUniquePatientId() { return uniquePatientId; }
    public void setUniquePatientId(String uniquePatientId) { this.uniquePatientId = uniquePatientId; }

    public UUID getAppointmentId() { return appointmentId; }
    public void setAppointmentId(UUID appointmentId) { this.appointmentId = appointmentId; }

    public BigDecimal getConsultationFee() { return consultationFee; }
    public void setConsultationFee(BigDecimal consultationFee) { this.consultationFee = consultationFee; }

    public BigDecimal getMedicineCharges() { return medicineCharges; }
    public void setMedicineCharges(BigDecimal medicineCharges) { this.medicineCharges = medicineCharges; }

    public BigDecimal getLabCharges() { return labCharges; }
    public void setLabCharges(BigDecimal labCharges) { this.labCharges = labCharges; }

    public BigDecimal getOtherCharges() { return otherCharges; }
    public void setOtherCharges(BigDecimal otherCharges) { this.otherCharges = otherCharges; }

    public BigDecimal getDiscountPercentage() { return discountPercentage; }
    public void setDiscountPercentage(BigDecimal discountPercentage) { this.discountPercentage = discountPercentage; }

    public BigDecimal getTaxPercentage() { return taxPercentage; }
    public void setTaxPercentage(BigDecimal taxPercentage) { this.taxPercentage = taxPercentage; }

    // ---- computed fields: getters only ----

    public BigDecimal getSubtotalAmount() { return subtotalAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public BigDecimal getTotalAmount() { return totalAmount; }

    // ---- payment ----

    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    // no setter — use domain methods (markPaid / markRefunded)

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    // no setter — set via markPaid

    public String getTransactionReference() { return transactionReference; }
    // no setter — set via markPaid

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getPaidAt() { return paidAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public Long getVersion() { return version; }
}