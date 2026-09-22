package com.apexcare.patient.controller;

import com.apexcare.patient.dto.ApiResponse;
import com.apexcare.patient.dto.CreateReferralRequest;
import com.apexcare.patient.dto.ReferralResponse;
import com.apexcare.patient.entity.ReferralStatus;
import com.apexcare.patient.security.JwtPrincipal;
import com.apexcare.patient.service.DoctorReferralService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/referrals")
@Tag(name = "Doctor-to-Doctor Referrals")
@SecurityRequirement(name = "bearerAuth")
public class ReferralController {

    private final DoctorReferralService referralService;

    public ReferralController(DoctorReferralService referralService) {
        this.referralService = referralService;
    }

    // -----------------------------------------------------------------
    // ADMIN — list everything (paginated)
    // -----------------------------------------------------------------
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List all hospital referrals (ADMIN only)")
    @SuppressWarnings("all")
    public ResponseEntity<ApiResponse<Page<ReferralResponse>>> getAll(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(ApiResponse.ok("Referrals retrieved",
                referralService.getAllReferrals(pageable)));
    }

    // -----------------------------------------------------------------
    // Doctor — my incoming referrals
    // -----------------------------------------------------------------
    @GetMapping("/incoming")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    @Operation(summary = "Get my incoming referrals")
    @SuppressWarnings("all")
    public ResponseEntity<ApiResponse<List<ReferralResponse>>> getMyIncoming(
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        UUID doctorId = principal.userId();
        return ResponseEntity.ok(ApiResponse.ok("Incoming referrals retrieved",
                referralService.getIncomingReferrals(doctorId)));
    }

    // -----------------------------------------------------------------
    // Doctor — my outgoing referrals
    // -----------------------------------------------------------------
    @GetMapping("/outgoing")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    @Operation(summary = "Get referrals I have initiated")
    @SuppressWarnings("all")
    public ResponseEntity<ApiResponse<List<ReferralResponse>>> getMyOutgoing(
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        UUID doctorId = principal.userId();
        return ResponseEntity.ok(ApiResponse.ok("Outgoing referrals retrieved",
                referralService.getOutgoingReferrals(doctorId)));
    }

    // -----------------------------------------------------------------
    // Doctor — create referral (referringDoctorId from principal)
    // -----------------------------------------------------------------
    @PostMapping
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    @Operation(summary = "Create inter-departmental specialist referral")
    public ResponseEntity<ApiResponse<ReferralResponse>> create(
            @Valid @RequestBody CreateReferralRequest request,
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        ReferralResponse saved = referralService.createReferral(request, principal);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Doctor referral created", saved));
    }

    // -----------------------------------------------------------------
    // Target doctor — accept / complete / decline / cancel
    // -----------------------------------------------------------------
    @PutMapping("/{referralId}/status")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    @Operation(summary = "Update referral status",
            description = "Only the target doctor (or ADMIN) can update. "
                    + "Valid transitions: PENDING→ACCEPTED|DECLINED|CANCELLED, "
                    + "ACCEPTED→COMPLETED|CANCELLED.")
    @SuppressWarnings("all")
    public ResponseEntity<ApiResponse<ReferralResponse>> updateStatus(
            @PathVariable UUID referralId,
            @RequestParam ReferralStatus status,
            @AuthenticationPrincipal JwtPrincipal principal
    ) {
        ReferralResponse updated = referralService.updateStatus(referralId, status, principal);
        return ResponseEntity.ok(ApiResponse.ok("Referral status updated", updated));
    }
}