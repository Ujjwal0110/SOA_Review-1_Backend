package com.apexcare.billing.service;

import com.apexcare.billing.dto.BillRequest;
import com.apexcare.billing.dto.BillResponse;
import com.apexcare.billing.dto.BillingSummaryResponse;
import com.apexcare.billing.dto.GenerateBillRequest;
import com.apexcare.billing.dto.PaymentReceiptResponse;
import com.apexcare.billing.dto.PaymentRequest;
import com.apexcare.billing.entity.Bill;
import com.apexcare.billing.entity.Payment;
import com.apexcare.billing.entity.PaymentStatus;
import com.apexcare.billing.entity.Role;
import com.apexcare.billing.exception.AuthorizationException;
import com.apexcare.billing.exception.InvalidRequestException;
import com.apexcare.billing.exception.ResourceNotFoundException;
import com.apexcare.billing.repository.BillRepository;
import com.apexcare.billing.repository.PaymentRepository;
import com.apexcare.billing.security.JwtPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final BillRepository billRepository;
    private final PaymentRepository paymentRepository;
    private final AuditLogService auditLogService;
    private final BigDecimal defaultConsultationFee;
    private final BigDecimal defaultTaxPercentage;

    public BillingService(
            BillRepository billRepository,
            PaymentRepository paymentRepository,
            AuditLogService auditLogService,
            @Value("${apexcare.billing.default-consultation-fee:500.00}")
            BigDecimal defaultConsultationFee,
            @Value("${apexcare.billing.default-tax-percentage:5.00}")
            BigDecimal defaultTaxPercentage
    ) {
        this.billRepository = billRepository;
        this.paymentRepository = paymentRepository;
        this.auditLogService = auditLogService;
        this.defaultConsultationFee = defaultConsultationFee;
        this.defaultTaxPercentage = defaultTaxPercentage;
    }

    // =================================================================
    // CREATE
    // =================================================================

    @Transactional
    public BillResponse createBill(BillRequest request, JwtPrincipal principal) {
        requireStaff(principal);

        // Idempotency: if a bill already exists for the appointment, return it
        if (request.getAppointmentId() != null) {
            Optional<Bill> existing = billRepository
                    .findFirstByAppointmentIdOrderByCreatedAtDesc(request.getAppointmentId());
            if (existing.isPresent()) {
                log.info("Bill already exists for appointmentId={}, returning existing",
                        request.getAppointmentId());
                return BillResponse.from(existing.get());
            }
        }

        Bill bill = buildBill(
                request.getPatientId(),
                request.getUniquePatientId(),
                request.getAppointmentId(),
                request.getConsultationFee(),
                request.getMedicineCharges(),
                request.getLabCharges(),
                request.getOtherCharges(),
                request.getDiscountPercentage(),
                request.getTaxPercentage(),
                request.getNotes(),
                "Standard Consultation Bill");

        Bill saved = billRepository.save(bill);

        auditLogService.record(principal, "CREATE_BILL", "BILL", saved.getId().toString(),
                "Bill created total=" + saved.getTotalAmount(), null);

        log.info("Bill created billId={} totalAmount={}",
                saved.getId(), saved.getTotalAmount());
        return BillResponse.from(saved);
    }

    @Transactional
    public BillResponse generateBillForAppointment(GenerateBillRequest request,
                                                   JwtPrincipal principal) {
        if (principal == null) {
            throw new AuthorizationException("Authentication required");
        }

        // Idempotency — appointment-service retries must not create duplicates
        Optional<Bill> existing = billRepository
                .findFirstByAppointmentIdOrderByCreatedAtDesc(request.getAppointmentId());
        if (existing.isPresent()) {
            log.info("Bill already generated for appointmentId={}, returning billId={}",
                    request.getAppointmentId(), existing.get().getId());
            return BillResponse.from(existing.get());
        }

        Bill bill = buildBill(
                request.getPatientId(),
                request.getUniquePatientId(),
                request.getAppointmentId(),
                request.getConsultationFee(),
                request.getMedicineCharges(),
                request.getLabCharges(),
                request.getOtherCharges(),
                request.getDiscountPercentage(),
                request.getTaxPercentage(),
                request.getNotes(),
                "Auto-generated Consultation Bill");

        Bill saved = billRepository.save(bill);

        auditLogService.record(principal, "GENERATE_BILL", "BILL", saved.getId().toString(),
                "Auto-generated bill for appointment " + request.getAppointmentId(), null);

        log.info("Auto-generated bill appointmentId={} billId={} totalAmount={}",
                request.getAppointmentId(), saved.getId(), saved.getTotalAmount());
        return BillResponse.from(saved);
    }

    // =================================================================
    // READ
    // =================================================================

    @Transactional(readOnly = true)
    public BillResponse getBillById(UUID id, JwtPrincipal principal) {
        Bill bill = billRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Bill", id));

        assertCanView(bill, principal);
        return BillResponse.from(bill);
    }

    @Transactional(readOnly = true)
    public List<BillResponse> getBillsByPatient(UUID patientId, JwtPrincipal principal) {
        if (principal == null) {
            throw new AuthorizationException("Authentication required");
        }
        // Patients may only access their own bills
        if (principal.isPatient() && !principal.isLinkedToPatient(patientId)) {
            // 404 not 403 — do not leak that the patient exists
            throw ResourceNotFoundException.of("Patient", patientId);
        }
        return billRepository.findAllByPatientIdOrderByCreatedAtDesc(patientId)
                .stream()
                .map(BillResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public BillResponse getBillByAppointment(UUID appointmentId, JwtPrincipal principal) {
        Bill bill = billRepository
                .findFirstByAppointmentIdOrderByCreatedAtDesc(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No bill found for appointment: " + appointmentId));

        assertCanView(bill, principal);
        return BillResponse.from(bill);
    }

    @Transactional(readOnly = true)
    public Page<BillResponse> getAllBills(PaymentStatus status, Pageable pageable) {
        Page<Bill> page = (status != null)
                ? billRepository.findByPaymentStatus(status, pageable)
                : billRepository.findAll(pageable);
        return page.map(BillResponse::from);
    }

    // =================================================================
    // PAYMENT
    // =================================================================

    @Transactional
    public PaymentReceiptResponse processPayment(UUID billId,
                                                 PaymentRequest request,
                                                 JwtPrincipal principal) {
        requireAuthenticated(principal);

        Bill bill = billRepository.findById(billId)
                .orElseThrow(() -> ResourceNotFoundException.of("Bill", billId));

        // IDOR — patients may only pay their own bills
        if (principal.isPatient() && !principal.isLinkedToPatient(bill.getPatientId())) {
            throw ResourceNotFoundException.of("Bill", billId);
        }

        if (bill.getPaymentStatus() == PaymentStatus.PAID) {
            throw new InvalidRequestException("This bill has already been paid");
        }
        if (bill.getPaymentStatus() == PaymentStatus.REFUNDED) {
            throw new InvalidRequestException("This bill has been refunded and cannot be paid");
        }

        // Verify amount matches bill total
        if (request.getAmount().compareTo(bill.getTotalAmount()) < 0) {
            throw new InvalidRequestException(
                    "Payment amount (" + request.getAmount()
                            + ") is less than bill total (" + bill.getTotalAmount() + ")");
        }

        // Generate a reference if not supplied (manual/cash entries)
        String txnRef = (request.getTransactionReference() != null
                && !request.getTransactionReference().isBlank())
                ? request.getTransactionReference()
                : "TXN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();

        // Reject duplicate transaction references early (DB constraint is the final guard)
        if (paymentRepository.existsByTransactionReference(txnRef)) {
            throw new InvalidRequestException(
                    "A payment with this transaction reference already exists");
        }

        // ---- Create Payment ----
        Payment payment = new Payment();
        payment.setBillId(bill.getId());
        payment.setPatientId(bill.getPatientId());
        payment.setAmount(request.getAmount());
        payment.setPaymentMethod(request.getPaymentMethod());
        payment.setTransactionReference(txnRef);
        payment.setNotes(request.getNotes());
        payment.setCreatedBy(principal.userId());
        payment.markPaid();   // domain method sets status = PAID

        Payment savedPayment = paymentRepository.save(payment);

        // ---- Update Bill via domain method ----
        bill.markPaid(request.getPaymentMethod(), txnRef);
        billRepository.save(bill);

        auditLogService.record(principal, "PAYMENT_RECEIVED", "BILL", bill.getId().toString(),
                "Payment " + request.getAmount() + " via " + request.getPaymentMethod()
                        + " txnRef=" + txnRef, null);

        log.info("Payment processed billId={} txnRef={} amount={}",
                bill.getId(), txnRef, request.getAmount());
        return PaymentReceiptResponse.from(bill, savedPayment);
    }

    @Transactional(readOnly = true)
    public PaymentReceiptResponse getReceipt(UUID billId, JwtPrincipal principal) {
        Bill bill = billRepository.findById(billId)
                .orElseThrow(() -> ResourceNotFoundException.of("Bill", billId));

        if (principal == null || (principal.isPatient()
                && !principal.isLinkedToPatient(bill.getPatientId()))) {
            throw ResourceNotFoundException.of("Bill", billId);
        }

        if (bill.getPaymentStatus() != PaymentStatus.PAID) {
            throw new InvalidRequestException(
                    "Receipt is available only for PAID bills (current: "
                            + bill.getPaymentStatus() + ")");
        }

        Payment payment = paymentRepository
                .findFirstByBillIdAndStatusOrderByPaymentDateDesc(
                        billId, PaymentStatus.PAID)
                .orElseThrow(() -> new IllegalStateException(
                        "PAID bill has no matching PAID payment record — data inconsistency"));

        return PaymentReceiptResponse.from(bill, payment);
    }

    // =================================================================
    // SUMMARY
    // =================================================================

    @Transactional(readOnly = true)
    public BillingSummaryResponse getSummaryStats(JwtPrincipal principal) {
        requireAdminOrBilling(principal);

        BillingSummaryResponse s = new BillingSummaryResponse();

        s.setTotalBills(billRepository.count());
        s.setPendingBills(billRepository.countByPaymentStatus(PaymentStatus.PENDING));
        s.setPaidBills(billRepository.countByPaymentStatus(PaymentStatus.PAID));
        s.setFailedBills(billRepository.countByPaymentStatus(PaymentStatus.FAILED));
        s.setRefundedBills(billRepository.countByPaymentStatus(PaymentStatus.REFUNDED));

        s.setTotalBilled(nz(billRepository.sumTotalBilledAmount()));
        s.setTotalRevenue(nz(billRepository.sumTotalAmountByStatus(PaymentStatus.PAID)));
        s.setPendingAmount(nz(billRepository.sumTotalAmountByStatus(PaymentStatus.PENDING)));
        s.setRefundedAmount(nz(billRepository.sumTotalAmountByStatus(PaymentStatus.REFUNDED)));

        return s;
    }

    // =================================================================
    // HELPERS
    // =================================================================

    private Bill buildBill(UUID patientId,
                           String uniquePatientId,
                           UUID appointmentId,
                           BigDecimal consultationFee,
                           BigDecimal medicineCharges,
                           BigDecimal labCharges,
                           BigDecimal otherCharges,
                           BigDecimal discountPercentage,
                           BigDecimal taxPercentage,
                           String notes,
                           String defaultNotes) {
        Bill bill = new Bill();
        bill.setPatientId(patientId);
        bill.setUniquePatientId(uniquePatientId);
        bill.setAppointmentId(appointmentId);

        bill.setConsultationFee(consultationFee != null
                ? consultationFee : defaultConsultationFee);
        bill.setMedicineCharges(medicineCharges != null
                ? medicineCharges : BigDecimal.ZERO);
        bill.setLabCharges(labCharges != null
                ? labCharges : BigDecimal.ZERO);
        bill.setOtherCharges(otherCharges != null
                ? otherCharges : BigDecimal.ZERO);
        bill.setDiscountPercentage(discountPercentage != null
                ? discountPercentage : BigDecimal.ZERO);
        bill.setTaxPercentage(taxPercentage != null
                ? taxPercentage : defaultTaxPercentage);
        bill.setNotes(notes != null ? notes : defaultNotes);

        // paymentStatus default PENDING set by entity's @PrePersist
        // subtotal/discount/tax/total computed by entity's @PrePersist
        return bill;
    }

    private static void requireAuthenticated(JwtPrincipal principal) {
        if (principal == null) {
            throw new AuthorizationException("Authentication required");
        }
    }

    private static void requireStaff(JwtPrincipal principal) {
        requireAuthenticated(principal);
        if (!principal.hasAnyRole(Role.ADMIN, Role.RECEPTIONIST, Role.BILLING_ADMIN)) {
            throw new AuthorizationException(
                    "Insufficient role to create bills");
        }
    }

    private static void requireAdminOrBilling(JwtPrincipal principal) {
        requireAuthenticated(principal);
        if (!principal.hasAnyRole(Role.ADMIN, Role.BILLING_ADMIN)) {
            throw new AuthorizationException(
                    "ADMIN or BILLING_ADMIN role required");
        }
    }

    private void assertCanView(Bill bill, JwtPrincipal principal) {
        requireAuthenticated(principal);
        if (principal.isPatient() && !principal.isLinkedToPatient(bill.getPatientId())) {
            // 404 not 403 — do not leak existence
            throw ResourceNotFoundException.of("Bill", bill.getId());
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}