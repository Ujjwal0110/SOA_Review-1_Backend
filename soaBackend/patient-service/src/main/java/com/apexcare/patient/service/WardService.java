package com.apexcare.patient.service;

import com.apexcare.patient.dto.AdmitPatientRequest;
import com.apexcare.patient.dto.BedResponse;
import com.apexcare.patient.dto.DischargeRequest;
import com.apexcare.patient.entity.AdmissionRecord;
import com.apexcare.patient.entity.AdmissionStatus;
import com.apexcare.patient.entity.AuditAction;
import com.apexcare.patient.entity.Bed;
import com.apexcare.patient.entity.BedStatus;
import com.apexcare.patient.entity.Patient;
import com.apexcare.patient.entity.ResourceType;
import com.apexcare.patient.entity.Role;
import com.apexcare.patient.entity.WardType;
import com.apexcare.patient.exception.AuthorizationException;
import com.apexcare.patient.exception.InvalidRequestException;
import com.apexcare.patient.exception.ResourceNotFoundException;
import com.apexcare.patient.repository.AdmissionRecordRepository;
import com.apexcare.patient.repository.BedRepository;
import com.apexcare.patient.repository.PatientRepository;
import com.apexcare.patient.security.JwtPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class WardService {

    private static final Logger log = LoggerFactory.getLogger(WardService.class);

    private final BedRepository bedRepository;
    private final AdmissionRecordRepository recordRepository;
    private final PatientRepository patientRepository;
    private final AuditLogService auditLogService;

    public WardService(BedRepository bedRepository,
                       AdmissionRecordRepository recordRepository,
                       PatientRepository patientRepository,
                       AuditLogService auditLogService) {
        this.bedRepository = bedRepository;
        this.recordRepository = recordRepository;
        this.patientRepository = patientRepository;
        this.auditLogService = auditLogService;
    }

    // -----------------------------------------------------------------
    // READ
    // -----------------------------------------------------------------
    @Transactional(readOnly = true)
    @SuppressWarnings("null")
    public Page<BedResponse> findBeds(WardType wardType,
                                      BedStatus status,
                                      Pageable pageable,
                                      JwtPrincipal principal) {
        requireStaff(principal);

        // Simple branching keeps repository methods clean and typed.
        if (wardType != null && status != null) {
            return bedRepository.findByWardTypeAndStatus(wardType, status, pageable)
                    .map(BedResponse::from);
        }
        if (wardType != null) {
            return bedRepository.findByWardType(wardType, pageable).map(BedResponse::from);
        }
        if (status != null) {
            return bedRepository.findByStatus(status, pageable).map(BedResponse::from);
        }
        return bedRepository.findAll(pageable).map(BedResponse::from);
    }

    @SuppressWarnings("null")
    public Page<BedResponse> findBeds(WardType wardType, BedStatus status, Pageable pageable) {
        return findBeds(wardType, status, pageable, null);
    }

    // -----------------------------------------------------------------
    // ADMIT — clinical action, race-protected by @Version
    // -----------------------------------------------------------------
    @Transactional
    @SuppressWarnings("null")
    public BedResponse admitPatient(UUID bedId,
                                    AdmitPatientRequest request,
                                    JwtPrincipal principal) {
        requireClinicalStaff(principal);

        Bed bed = bedRepository.findById(bedId)
                .orElseThrow(() -> ResourceNotFoundException.of("Bed", bedId));

        if (bed.getStatus() != BedStatus.AVAILABLE) {
            throw new InvalidRequestException(
                    "Bed " + bed.getBedNumber() + " is " + bed.getStatus() + ", not available");
        }

        UUID patientId = request.patientId();
        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> ResourceNotFoundException.of("Patient", patientId));

        // Patient must not already be admitted
        if (bedRepository.existsByCurrentPatientIdAndStatus(
                patient.getId(), BedStatus.OCCUPIED)) {
            throw new InvalidRequestException(
                    "Patient is already admitted in another bed");
        }

        // ---- Update bed ----
        Instant now = Instant.now();
        bed.setStatus(BedStatus.OCCUPIED);
        bed.setCurrentPatientId(patient.getId());
        bed.setCurrentPatientName(patient.getFirstName() + " " + patient.getLastName());
        bed.setLastOccupiedAt(now);
        Bed savedBed = bedRepository.save(bed);   // @Version guards concurrent admits

        // ---- Write admission record (snapshot semantics) ----
        AdmissionRecord record = new AdmissionRecord();
        record.setPatientId(patient.getId());
        record.setBedId(bed.getId());
        record.setBedNumber(bed.getBedNumber());
        record.setWardType(bed.getWardType());
        record.setPatientName(patient.getFirstName() + " " + patient.getLastName());
        record.setAdmittingDoctorId(principal.userId());
        record.setAdmissionReason(request.reason());
        record.setStatus(AdmissionStatus.ADMITTED);
        record.setCreatedBy(principal.userId());
        recordRepository.save(record);

        // ---- Audit ----
        auditLogService.record(principal, AuditAction.ADMIT_PATIENT,
                ResourceType.ADMISSION_RECORD, record.getId().toString(),
                "Admitted patient " + patient.getId() + " to bed " + bed.getBedNumber(),
                null);

        log.info("Patient admitted patientId={} bedId={} bedNumber={} by={}",
                patient.getId(), bed.getId(), bed.getBedNumber(), principal.userId());

        return BedResponse.from(savedBed);
    }

    // -----------------------------------------------------------------
    // DISCHARGE
    // -----------------------------------------------------------------
    @Transactional
    @SuppressWarnings("null")
    public BedResponse dischargePatient(UUID bedId,
                                        DischargeRequest request,
                                        JwtPrincipal principal) {
        requireClinicalStaff(principal);

        Bed bed = bedRepository.findById(bedId)
                .orElseThrow(() -> ResourceNotFoundException.of("Bed", bedId));

        if (bed.getStatus() != BedStatus.OCCUPIED) {
            throw new InvalidRequestException(
                    "Bed " + bed.getBedNumber() + " is not currently occupied");
        }

        AdmissionRecord record = recordRepository
                .findByBedIdAndStatus(bedId, AdmissionStatus.ADMITTED)
                .orElseThrow(() -> new InvalidRequestException(
                        "No active admission record found for bed " + bed.getBedNumber()));

        // ---- Update admission record ----
        Instant now = Instant.now();
        record.setDischargedAt(now);
        record.setDischargeSummary(request.dischargeSummary());
        record.setStatus(AdmissionStatus.DISCHARGED);
        record.setUpdatedBy(principal.userId());
        recordRepository.save(record);

        // ---- Update bed ----
        bed.setStatus(BedStatus.CLEANING);
        bed.setCurrentPatientId(null);
        bed.setCurrentPatientName(null);
        bed.setLastDischargedAt(now);
        Bed savedBed = bedRepository.save(bed);

        // ---- Audit ----
        auditLogService.record(principal, AuditAction.DISCHARGE_PATIENT,
                ResourceType.ADMISSION_RECORD, record.getId().toString(),
                "Discharged patient from bed " + bed.getBedNumber(), null);

        log.info("Patient discharged bedId={} bedNumber={} by={}",
                bed.getId(), bed.getBedNumber(), principal.userId());

        return BedResponse.from(savedBed);
    }

    // -----------------------------------------------------------------
    // MARK READY (housekeeping)
    // -----------------------------------------------------------------
    @Transactional
    @SuppressWarnings("null")
    public BedResponse markBedReady(UUID bedId, JwtPrincipal principal) {
        requireNurseOrAdmin(principal);

        Bed bed = bedRepository.findById(bedId)
                .orElseThrow(() -> ResourceNotFoundException.of("Bed", bedId));

        if (bed.getStatus() != BedStatus.CLEANING) {
            throw new InvalidRequestException(
                    "Bed " + bed.getBedNumber() + " must be in CLEANING state to be marked ready. "
                            + "Current: " + bed.getStatus());
        }

        Instant now = Instant.now();
        bed.setStatus(BedStatus.AVAILABLE);
        bed.setLastSanitizedAt(now);
        bed.setLastSanitizedBy(principal.userId());
        Bed savedBed = bedRepository.save(bed);

        auditLogService.record(principal, AuditAction.MARK_BED_READY,
                ResourceType.BED, bed.getId().toString(),
                "Marked bed " + bed.getBedNumber() + " ready for next patient", null);

        log.info("Bed marked ready bedId={} bedNumber={} by={}",
                bed.getId(), bed.getBedNumber(), principal.userId());

        return BedResponse.from(savedBed);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------
    private static void requireStaff(JwtPrincipal principal) {
        if (principal == null
                || !principal.hasAnyRole(Role.ADMIN, Role.DOCTOR,
                                         Role.NURSE, Role.RECEPTIONIST)) {
            throw new AuthorizationException("Insufficient role to access ward data");
        }
    }

    private static void requireClinicalStaff(JwtPrincipal principal) {
        if (principal == null
                || !principal.hasAnyRole(Role.ADMIN, Role.DOCTOR, Role.NURSE)) {
            throw new AuthorizationException(
                    "Only clinical staff (ADMIN/DOCTOR/NURSE) can perform this action");
        }
    }

    private static void requireNurseOrAdmin(JwtPrincipal principal) {
        if (principal == null
                || !principal.hasAnyRole(Role.ADMIN, Role.NURSE)) {
            throw new AuthorizationException(
                    "Only NURSE or ADMIN can mark a bed ready");
        }
    }
}