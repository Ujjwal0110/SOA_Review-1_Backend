package com.apexcare.billing.controller;

import com.apexcare.billing.dto.ApiResponse;
import com.apexcare.billing.dto.PharmacyItemResponse;
import com.apexcare.billing.entity.PharmacyItem;
import com.apexcare.billing.exception.InvalidRequestException;
import com.apexcare.billing.security.JwtPrincipal;
import com.apexcare.billing.service.PharmacyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/billing/pharmacy")
@Tag(name = "Pharmacy & Medicine Inventory",
     description = "Medicine inventory, stock operations, and dispensing")
@SecurityRequirement(name = "bearerAuth")
public class PharmacyController {

    private final PharmacyService pharmacyService;

    public PharmacyController(PharmacyService pharmacyService) {
        this.pharmacyService = pharmacyService;
    }

    // =================================================================
    // READ
    // =================================================================

    @GetMapping("/inventory")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR','NURSE','PHARMACIST','RECEPTIONIST')")
    @Operation(summary = "List medicine inventory (staff only, paginated)")
    public ResponseEntity<ApiResponse<Page<PharmacyItemResponse>>> getInventory(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "medicineName", direction = Sort.Direction.ASC)
            Pageable pageable,
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        Page<PharmacyItemResponse> page = pharmacyService
                .getInventory(category, search, pageable, principal)
                .map(PharmacyItemResponse::from);
        return ResponseEntity.ok(ApiResponse.ok("Inventory retrieved", page));
    }

    @GetMapping("/inventory/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR','NURSE','PHARMACIST','RECEPTIONIST')")
    @Operation(summary = "Get a single pharmacy item by id")
    public ResponseEntity<ApiResponse<PharmacyItemResponse>> getItem(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        PharmacyItem item = pharmacyService.getItem(id, principal);
        return ResponseEntity.ok(ApiResponse.ok("Item retrieved",
                PharmacyItemResponse.from(item)));
    }

    @GetMapping("/low-stock")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST','NURSE')")
    @Operation(summary = "List medicines at or below reorder threshold")
    public ResponseEntity<ApiResponse<List<PharmacyItemResponse>>> getLowStockItems(
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        List<PharmacyItemResponse> items = pharmacyService
                .getLowStockItems(principal)
                .stream()
                .map(PharmacyItemResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok("Low stock items retrieved", items));
    }

    @GetMapping("/expiring-soon")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    @Operation(summary = "List items expiring within N days (default 30)")
    public ResponseEntity<ApiResponse<List<PharmacyItemResponse>>> getExpiringSoon(
            @RequestParam(defaultValue = "30") @Min(1) int days,
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        List<PharmacyItemResponse> items = pharmacyService
                .getExpiringSoon(days, principal)
                .stream()
                .map(PharmacyItemResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(
                "Items expiring within " + days + " days retrieved", items));
    }

    // =================================================================
    // WRITE — inventory management (admin/pharmacist only)
    // =================================================================

    @PostMapping("/inventory")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    @Operation(summary = "Add or update a pharmacy medicine (ADMIN/PHARMACIST only)")
    public ResponseEntity<ApiResponse<PharmacyItemResponse>> addOrUpdate(
            @RequestBody AddPharmacyItemRequest request,
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        PharmacyItem saved = pharmacyService.addOrUpdateItem(request, principal);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Medicine item saved",
                        PharmacyItemResponse.from(saved)));
    }

    // =================================================================
    // WRITE — stock operations
    // =================================================================

    @PostMapping("/dispense")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST','NURSE','DOCTOR')")
    @Operation(
            summary = "Dispense medicine and decrement inventory stock",
            description = "Throws 409 if insufficient stock. Concurrency-safe via "
                    + "optimistic locking."
    )
    public ResponseEntity<ApiResponse<PharmacyItemResponse>> dispense(
            @RequestBody DispenseRequest request,
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        PharmacyItem updated = pharmacyService.dispenseMedicine(
                request.itemId(), request.quantity(), principal);
        return ResponseEntity.ok(ApiResponse.ok("Medicine dispensed successfully",
                PharmacyItemResponse.from(updated)));
    }

    @PostMapping("/restock")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    @Operation(summary = "Restock medicine inventory (ADMIN/PHARMACIST only)")
    public ResponseEntity<ApiResponse<PharmacyItemResponse>> restock(
            @RequestBody RestockRequest request,
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        PharmacyItem updated = pharmacyService.restockMedicine(
                request.itemId(), request.quantity(), principal);
        return ResponseEntity.ok(ApiResponse.ok("Medicine restocked successfully",
                PharmacyItemResponse.from(updated)));
    }

    @PostMapping("/adjust")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Adjust stock to an exact value (ADMIN only)",
            description = "Used for stock-take reconciliation. Every adjustment is audited."
    )
    public ResponseEntity<ApiResponse<PharmacyItemResponse>> adjust(
            @RequestBody AdjustStockRequest request,
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        PharmacyItem updated = pharmacyService.adjustStock(
                request.itemId(), request.newQuantity(), request.reason(), principal);
        return ResponseEntity.ok(ApiResponse.ok("Stock adjusted",
                PharmacyItemResponse.from(updated)));
    }

    // =================================================================
    // Request DTOs
    // =================================================================

    public record AddPharmacyItemRequest(
            String medicineName,
            String genericName,
            String category,
            String dosageForm,
            String strength,
            Integer stockQuantity,
            Integer reorderThreshold,
            BigDecimal unitPrice,
            String batchNumber,
            LocalDate expiryDate,
            String manufacturer
    ) {
        public AddPharmacyItemRequest {
            if (medicineName == null || medicineName.isBlank()) {
                throw new InvalidRequestException("medicineName is required");
            }
            if (genericName == null || genericName.isBlank()) {
                throw new InvalidRequestException("genericName is required");
            }
            if (category == null || category.isBlank()) {
                throw new InvalidRequestException("category is required");
            }
            if (dosageForm == null || dosageForm.isBlank()) {
                throw new InvalidRequestException("dosageForm is required");
            }
            if (strength == null || strength.isBlank()) {
                throw new InvalidRequestException("strength is required");
            }
            if (unitPrice == null || unitPrice.signum() < 0) {
                throw new InvalidRequestException("unitPrice must be zero or positive");
            }
            if (stockQuantity != null && stockQuantity < 0) {
                throw new InvalidRequestException("stockQuantity cannot be negative");
            }
            if (reorderThreshold != null && reorderThreshold < 0) {
                throw new InvalidRequestException("reorderThreshold cannot be negative");
            }
        }
    }

    public record DispenseRequest(
            UUID itemId,
            Integer quantity
    ) {
        public DispenseRequest {
            if (itemId == null) {
                throw new InvalidRequestException("itemId is required");
            }
            if (quantity == null || quantity <= 0) {
                throw new InvalidRequestException("quantity must be greater than zero");
            }
        }
    }

    public record RestockRequest(
            UUID itemId,
            Integer quantity
    ) {
        public RestockRequest {
            if (itemId == null) {
                throw new InvalidRequestException("itemId is required");
            }
            if (quantity == null || quantity <= 0) {
                throw new InvalidRequestException("quantity must be greater than zero");
            }
        }
    }

    public record AdjustStockRequest(
            UUID itemId,
            Integer newQuantity,
            String reason
    ) {
        public AdjustStockRequest {
            if (itemId == null) {
                throw new InvalidRequestException("itemId is required");
            }
            if (newQuantity == null || newQuantity < 0) {
                throw new InvalidRequestException(
                        "newQuantity must be zero or positive");
            }
            if (reason == null || reason.isBlank()) {
                throw new InvalidRequestException(
                        "reason is required for stock adjustments");
            }
        }
    }
}