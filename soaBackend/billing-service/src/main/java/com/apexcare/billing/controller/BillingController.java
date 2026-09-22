package com.apexcare.billing.controller;

import com.apexcare.billing.dto.ApiResponse;
import com.apexcare.billing.dto.BillRequest;
import com.apexcare.billing.dto.BillResponse;
import com.apexcare.billing.dto.BillingSummaryResponse;
import com.apexcare.billing.dto.GenerateBillRequest;
import com.apexcare.billing.dto.PaymentReceiptResponse;
import com.apexcare.billing.dto.PaymentRequest;
import com.apexcare.billing.entity.PaymentStatus;
import com.apexcare.billing.security.JwtPrincipal;
import com.apexcare.billing.service.BillingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/billing")
@Tag(name = "Billing", description = "Patient billing, invoice generation, charge calculation, and payment processing")
@SecurityRequirement(name = "bearerAuth")
public class BillingController {

    private static final int MAX_PAGE_SIZE = 100;

    /** Allow-list of sortable fields — prevents PropertyReferenceException and
     *  index misses from arbitrary client-supplied field names. */
    private static final Set<String> SORTABLE_FIELDS = Set.of(
            "createdAt", "updatedAt", "paidAt", "totalAmount", "paymentStatus");

    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    // =================================================================
    // CREATE
    // =================================================================

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTIONIST','BILLING_ADMIN')")
    @Operation(summary = "Create a custom bill/invoice (staff only)")
    public ResponseEntity<ApiResponse<BillResponse>> createBill(
            @Valid @RequestBody BillRequest request,
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        BillResponse response = billingService.createBill(request, principal);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Bill created successfully", response));
    }

    @PostMapping({"/generate", "/from-appointment"})
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Auto-generate bill from appointment",
            description = "Called by appointment-service via Feign or by reception staff. "
                    + "The caller's identity is used for the audit trail."
    )
    public ResponseEntity<ApiResponse<BillResponse>> generateBillForAppointment(
            @Valid @RequestBody GenerateBillRequest request,
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        BillResponse response = billingService.generateBillForAppointment(request, principal);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Bill generated successfully", response));
    }

    // =================================================================
    // READ
    // =================================================================

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get bill details by ID",
               description = "Patients may only access their own bills. Staff may access any.")
    public ResponseEntity<ApiResponse<BillResponse>> getBillById(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        BillResponse response = billingService.getBillById(id, principal);
        return ResponseEntity.ok(ApiResponse.ok("Bill retrieved successfully", response));
    }

    @GetMapping("/patient/{patientId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get all bills for a specific patient",
               description = "Patients may only access their own bills. Staff may access any.")
    public ResponseEntity<ApiResponse<List<BillResponse>>> getBillsByPatient(
            @PathVariable UUID patientId,
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        List<BillResponse> bills = billingService.getBillsByPatient(patientId, principal);
        return ResponseEntity.ok(ApiResponse.ok("Patient bills retrieved successfully", bills));
    }

    @GetMapping("/appointment/{appointmentId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get bill associated with an appointment")
    public ResponseEntity<ApiResponse<BillResponse>> getBillByAppointment(
            @PathVariable UUID appointmentId,
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        BillResponse response = billingService.getBillByAppointment(appointmentId, principal);
        return ResponseEntity.ok(ApiResponse.ok("Appointment bill retrieved successfully", response));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','BILLING_ADMIN','RECEPTIONIST')")
    @Operation(summary = "List all bills with optional payment status filter (staff only)")
    public ResponseEntity<ApiResponse<Page<BillResponse>>> getAllBills(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction
    ) {
        String safeSortBy = SORTABLE_FIELDS.contains(sortBy) ? sortBy : "createdAt";
        Sort.Direction dir = "asc".equalsIgnoreCase(direction)
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, safeSortBy));
        Page<BillResponse> bills = billingService.getAllBills(status, pageable);
        return ResponseEntity.ok(ApiResponse.ok("Bills retrieved successfully", bills));
    }

    // =================================================================
    // PAYMENT
    // =================================================================

    @PostMapping("/{id}/pay")
    @PreAuthorize("hasAnyRole('ADMIN','BILLING_ADMIN','RECEPTIONIST','PATIENT','DOCTOR')")
    @Operation(summary = "Process payment for a bill",
               description = "Patients may only pay their own bills. Staff may process any.")
    public ResponseEntity<ApiResponse<PaymentReceiptResponse>> processPayment(
            @PathVariable UUID id,
            @Valid @RequestBody PaymentRequest request,
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        PaymentReceiptResponse receipt = billingService.processPayment(id, request, principal);
        return ResponseEntity.ok(ApiResponse.ok("Payment processed successfully", receipt));
    }

    @GetMapping("/{id}/receipt")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get formatted receipt for a bill",
               description = "Patients may only fetch their own receipts.")
    public ResponseEntity<ApiResponse<PaymentReceiptResponse>> getReceipt(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        PaymentReceiptResponse receipt = billingService.getReceipt(id, principal);
        return ResponseEntity.ok(ApiResponse.ok("Receipt retrieved successfully", receipt));
    }

    // =================================================================
    // STATS
    // =================================================================

    @GetMapping("/stats/summary")
    @PreAuthorize("hasAnyRole('ADMIN','BILLING_ADMIN')")
    @Operation(summary = "Get billing revenue and invoice statistics summary (ADMIN/BILLING_ADMIN only)")
    public ResponseEntity<ApiResponse<BillingSummaryResponse>> getBillingSummary(
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        BillingSummaryResponse summary = billingService.getSummaryStats(principal);
        return ResponseEntity.ok(ApiResponse.ok("Billing summary retrieved successfully", summary));
    }
}