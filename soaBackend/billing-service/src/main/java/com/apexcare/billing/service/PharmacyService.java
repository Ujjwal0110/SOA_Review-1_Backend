package com.apexcare.billing.service;

import com.apexcare.billing.controller.PharmacyController.AddPharmacyItemRequest;
import com.apexcare.billing.entity.PharmacyItem;
import com.apexcare.billing.entity.Role;
import com.apexcare.billing.exception.AuthorizationException;
import com.apexcare.billing.exception.InvalidRequestException;
import com.apexcare.billing.exception.ResourceNotFoundException;
import com.apexcare.billing.repository.PharmacyItemRepository;
import com.apexcare.billing.security.JwtPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class PharmacyService {

    private static final Logger log = LoggerFactory.getLogger(PharmacyService.class);

    private final PharmacyItemRepository repository;
    private final AuditLogService auditLogService;

    public PharmacyService(PharmacyItemRepository repository,
                           AuditLogService auditLogService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
    }

    // =================================================================
    // READ
    // =================================================================

    @Transactional(readOnly = true)
    public Page<PharmacyItem> getInventory(String category,
                                           String search,
                                           Pageable pageable,
                                           JwtPrincipal principal) {
        requireStaff(principal);

        if (search != null && !search.isBlank()) {
            return repository.searchByNameOrGeneric(search.trim(), pageable);
        }
        if (category != null && !category.isBlank()) {
            return repository.findByCategory(category, pageable);
        }
        return repository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public PharmacyItem getItem(UUID id, JwtPrincipal principal) {
        requireStaff(principal);
        return repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("PharmacyItem", id));
    }

    @Transactional(readOnly = true)
    public List<PharmacyItem> getLowStockItems(JwtPrincipal principal) {
        requireStaff(principal);
        return repository.findLowStockItems();
    }

    @Transactional(readOnly = true)
    public List<PharmacyItem> getExpiringSoon(int days, JwtPrincipal principal) {
        requireStaff(principal);
        LocalDate today = LocalDate.now();
        LocalDate cutoff = today.plusDays(days);
        return repository.findByExpiryDateBetweenOrderByExpiryDateAsc(today, cutoff);
    }

    // =================================================================
    // WRITE — inventory management
    // =================================================================

    @Transactional
    public PharmacyItem addOrUpdateItem(AddPharmacyItemRequest request, JwtPrincipal principal) {
        requirePharmacistOrAdmin(principal);

        // Same medicine + strength + batch = same physical batch → update existing
        PharmacyItem item = repository
                .findFirstByMedicineNameAndStrengthAndBatchNumber(
                        request.medicineName(), request.strength(), request.batchNumber())
                .orElseGet(PharmacyItem::new);

        boolean isNew = item.getId() == null;

        if (isNew) {
            item.setMedicineName(request.medicineName());
            item.setGenericName(request.genericName());
            item.setCategory(com.apexcare.billing.entity.MedicineCategory.valueOf(request.category().toUpperCase()));
            item.setDosageForm(com.apexcare.billing.entity.DosageForm.valueOf(request.dosageForm().toUpperCase()));
            item.setStrength(request.strength());
            item.setBatchNumber(request.batchNumber());
            item.setManufacturer(request.manufacturer());
            item.setCreatedBy(principal.userId());
            item.setStockQuantity(request.stockQuantity() != null ? request.stockQuantity() : 0);
        }

        // These are always updatable
        item.setUnitPrice(request.unitPrice());
        item.setReorderThreshold(request.reorderThreshold() != null
                ? request.reorderThreshold() : 20);
        item.setExpiryDate(request.expiryDate());

        PharmacyItem saved = repository.save(item);

        auditLogService.record(principal,
                isNew ? "ADD_PHARMACY_ITEM" : "UPDATE_PHARMACY_ITEM",
                "PHARMACY_ITEM", saved.getId().toString(),
                (isNew ? "Added" : "Updated") + " " + saved.getMedicineName()
                        + " (" + saved.getStrength() + ") batch=" + saved.getBatchNumber(),
                null);

        return saved;
    }

    // =================================================================
    // WRITE — stock operations
    // =================================================================

    @Transactional
    public PharmacyItem dispenseMedicine(UUID itemId, int quantity, JwtPrincipal principal) {
        requireClinicalOrPharmacy(principal);

        if (quantity <= 0) {
            throw new InvalidRequestException("Quantity must be greater than zero");
        }

        PharmacyItem item = repository.findById(itemId)
                .orElseThrow(() -> ResourceNotFoundException.of("PharmacyItem", itemId));

        if (item.isExpired()) {
            throw new InvalidRequestException(
                    "Cannot dispense expired medicine: " + item.getMedicineName()
                            + " (expired " + item.getExpiryDate() + ")");
        }

        item.dispense(quantity);   // throws if insufficient; @Version guards race
        PharmacyItem saved = repository.save(item);

        auditLogService.record(principal, "DISPENSE_MEDICINE", "PHARMACY_ITEM",
                saved.getId().toString(),
                "Dispensed " + quantity + " of " + saved.getMedicineName()
                        + " (remaining " + saved.getStockQuantity() + ")",
                null);

        if (saved.isLowStock()) {
            log.warn("LOW STOCK itemId={} name={} remaining={} threshold={}",
                    saved.getId(), saved.getMedicineName(),
                    saved.getStockQuantity(), saved.getReorderThreshold());
        }

        return saved;
    }

    @Transactional
    public PharmacyItem restockMedicine(UUID itemId, int quantity, JwtPrincipal principal) {
        requirePharmacistOrAdmin(principal);

        if (quantity <= 0) {
            throw new InvalidRequestException("Quantity must be greater than zero");
        }

        PharmacyItem item = repository.findById(itemId)
                .orElseThrow(() -> ResourceNotFoundException.of("PharmacyItem", itemId));

        item.replenish(quantity);
        PharmacyItem saved = repository.save(item);

        auditLogService.record(principal, "REPLENISH_STOCK", "PHARMACY_ITEM",
                saved.getId().toString(),
                "Restocked " + quantity + " of " + saved.getMedicineName()
                        + " (new total " + saved.getStockQuantity() + ")",
                null);

        return saved;
    }

    @Transactional
    public PharmacyItem adjustStock(UUID itemId, int newQuantity, String reason, JwtPrincipal principal) {
        requireAdmin(principal);

        if (newQuantity < 0) {
            throw new InvalidRequestException("newQuantity cannot be negative");
        }
        if (reason == null || reason.isBlank()) {
            throw new InvalidRequestException("reason is required for stock adjustments");
        }

        PharmacyItem item = repository.findById(itemId)
                .orElseThrow(() -> ResourceNotFoundException.of("PharmacyItem", itemId));

        int previous = item.getStockQuantity();
        item.adjustTo(newQuantity);
        PharmacyItem saved = repository.save(item);

        auditLogService.record(principal, "ADJUST_STOCK", "PHARMACY_ITEM",
                saved.getId().toString(),
                "Adjusted " + saved.getMedicineName() + " from "
                        + previous + " to " + newQuantity + " — reason: " + reason,
                null);

        return saved;
    }

    // =================================================================
    // Authorization helpers
    // =================================================================

    private static void requireAdmin(JwtPrincipal principal) {
        requireAuthenticated(principal);
        if (!principal.hasRole(Role.ADMIN)) {
            throw new AuthorizationException("ADMIN role required");
        }
    }

    private static void requirePharmacistOrAdmin(JwtPrincipal principal) {
        requireAuthenticated(principal);
        if (!principal.hasAnyRole(Role.ADMIN, Role.PHARMACIST)) {
            throw new AuthorizationException(
                    "ADMIN or PHARMACIST role required");
        }
    }

    private static void requireClinicalOrPharmacy(JwtPrincipal principal) {
        requireAuthenticated(principal);
        if (!principal.hasAnyRole(Role.ADMIN, Role.PHARMACIST, Role.NURSE, Role.DOCTOR)) {
            throw new AuthorizationException(
                    "ADMIN, PHARMACIST, NURSE, or DOCTOR role required");
        }
    }

    private static void requireStaff(JwtPrincipal principal) {
        requireAuthenticated(principal);
        if (!principal.hasAnyRole(
                Role.ADMIN, Role.PHARMACIST, Role.NURSE,
                Role.DOCTOR, Role.RECEPTIONIST)) {
            throw new AuthorizationException("Insufficient role to access pharmacy inventory");
        }
    }

    private static void requireAuthenticated(JwtPrincipal principal) {
        if (principal == null) {
            throw new AuthorizationException("Authentication required");
        }
    }
}