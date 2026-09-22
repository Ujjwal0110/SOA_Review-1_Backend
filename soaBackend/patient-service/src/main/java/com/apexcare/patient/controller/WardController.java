package com.apexcare.patient.controller;

import com.apexcare.patient.dto.*;
import com.apexcare.patient.entity.BedStatus;
import com.apexcare.patient.entity.WardType;
import com.apexcare.patient.security.JwtPrincipal;
import com.apexcare.patient.service.WardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/wards")
@Tag(name = "Inpatient Ward & Bed ADT Management")
@SecurityRequirement(name = "bearerAuth")
public class WardController {

    private final WardService wardService;

    public WardController(WardService wardService) {
        this.wardService = wardService;
    }

    // -----------------------------------------------------------------
    // READ — beds matrix (staff only)
    // -----------------------------------------------------------------
    @GetMapping("/beds")
    @PreAuthorize("hasAnyRole('DOCTOR','NURSE','ADMIN','RECEPTIONIST')")
    @Operation(summary = "List hospital beds (filterable, paginated)")
    public ResponseEntity<ApiResponse<Page<BedResponse>>> getAllBeds(
            @RequestParam(required = false) WardType wardType,
            @RequestParam(required = false) BedStatus status,
            @PageableDefault(size = 50, sort = "bedNumber") Pageable pageable
    ) {
        return ResponseEntity.ok(ApiResponse.ok("Beds retrieved",
                wardService.findBeds(wardType, status, pageable)));
    }

    // -----------------------------------------------------------------
    // ADMIT — clinical action
    // -----------------------------------------------------------------
    @PostMapping("/beds/{bedId}/admit")
    @PreAuthorize("hasAnyRole('DOCTOR','NURSE','ADMIN')")
    @Operation(summary = "Admit patient into an inpatient bed")
    public ResponseEntity<ApiResponse<BedResponse>> admitPatient(
            @PathVariable UUID bedId,
            @Valid @RequestBody AdmitPatientRequest request,
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        BedResponse bed = wardService.admitPatient(bedId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Patient admitted to bed successfully", bed));
    }

    // -----------------------------------------------------------------
    // DISCHARGE — clinical action, mandatory summary
    // -----------------------------------------------------------------
    @PostMapping("/beds/{bedId}/discharge")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    @Operation(summary = "Discharge patient from inpatient bed")
    public ResponseEntity<ApiResponse<BedResponse>> dischargePatient(
            @PathVariable UUID bedId,
            @Valid @RequestBody DischargeRequest request,
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        BedResponse bed = wardService.dischargePatient(bedId, request, principal);
        return ResponseEntity.ok(ApiResponse.ok("Patient discharged from bed", bed));
    }

    // -----------------------------------------------------------------
    // READY — housekeeping / nurse
    // -----------------------------------------------------------------
    @PostMapping("/beds/{bedId}/ready")
    @PreAuthorize("hasAnyRole('NURSE','ADMIN')")
    @Operation(summary = "Mark sanitized bed ready for next patient")
    public ResponseEntity<ApiResponse<BedResponse>> markReady(
            @PathVariable UUID bedId,
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        BedResponse bed = wardService.markBedReady(bedId, principal);
        return ResponseEntity.ok(ApiResponse.ok("Bed marked available", bed));
    }
}