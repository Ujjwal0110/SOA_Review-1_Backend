package com.apexcare.patient.service;

import com.apexcare.patient.dto.PatientRequest;
import com.apexcare.patient.dto.PatientResponse;
import com.apexcare.patient.entity.AuditAction;
import com.apexcare.patient.entity.Patient;
import com.apexcare.patient.entity.PatientStatus;
import com.apexcare.patient.entity.ResourceType;
import com.apexcare.patient.exception.AuthorizationException;
import com.apexcare.patient.exception.InvalidRequestException;
import com.apexcare.patient.exception.ResourceNotFoundException;
import com.apexcare.patient.repository.PatientRepository;
import com.apexcare.patient.security.JwtPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class PatientService {

    private static final Logger log = LoggerFactory.getLogger(PatientService.class);

    private final PatientRepository repository;
    private final UniquePatientIdService uniquePatientIdService;
    private final AuditLogService auditLogService;

    public PatientService(PatientRepository repository,
                          UniquePatientIdService uniquePatientIdService,
                          AuditLogService auditLogService) {
        this.repository = repository;
        this.uniquePatientIdService = uniquePatientIdService;
        this.auditLogService = auditLogService;
    }

    // -----------------------------------------------------------------
    // CREATE — staff registers a new patient
    // -----------------------------------------------------------------
    @Transactional
    @SuppressWarnings("all")
    public PatientResponse create(PatientRequest request, JwtPrincipal principal) {
        requireStaff(principal);

        Patient patient = new Patient();
        applyEditableFields(patient, request);
        patient.setStatus(PatientStatus.ACTIVE);
        assignUniqueId(patient);

        Patient saved = repository.save(patient);

        auditLogService.record(principal, AuditAction.CREATE_PATIENT,
                ResourceType.PATIENT_EMR, saved.getId().toString(),
                "Patient registered: " + saved.getUniquePatientId(), null);

        log.info("Patient registered id={} uniquePatientId={}",
                saved.getId(), saved.getUniquePatientId());
        return PatientResponse.from(saved);
    }

    // -----------------------------------------------------------------
    // CREATE — patient self-registers own record (linked account)
    // -----------------------------------------------------------------
    @Transactional
    @SuppressWarnings("all")
    public PatientResponse createForLinkedAccount(PatientRequest request, JwtPrincipal principal) {
        if (principal == null || !principal.isPatient()) {
            throw new AuthorizationException(
                    "Only patient accounts can self-register a medical record");
        }
        if (principal.linkedPatientId() == null) {
            throw new InvalidRequestException(
                    "Patient account is not linked to a medical record identifier");
        }

        // Idempotent: return existing record if already registered
        UUID linkedId = principal.linkedPatientId();
        return repository.findById(linkedId)
                .map(PatientResponse::from)
                .orElseGet(() -> {
                    Patient patient = new Patient();
                    applyEditableFields(patient, request);
                    patient.setId(principal.linkedPatientId());
                    patient.setStatus(PatientStatus.ACTIVE);
                    assignUniqueId(patient);

                    Patient saved = repository.save(patient);

                    auditLogService.record(principal, AuditAction.CREATE_PATIENT,
                            ResourceType.PATIENT_EMR, saved.getId().toString(),
                            "Patient self-registered: " + saved.getUniquePatientId(), null);

                    log.info("Self-registered patient id={} uniquePatientId={}",
                            saved.getId(), saved.getUniquePatientId());
                    return PatientResponse.from(saved);
                });
    }

    // -----------------------------------------------------------------
    // READ — list / search
    // -----------------------------------------------------------------
    @Transactional(readOnly = true)
    @SuppressWarnings("all")
    public Page<PatientResponse> list(String q,
                                        PatientStatus status,
                                        Pageable pageable,
                                        JwtPrincipal principal) {
        requireStaff(principal);

        String normalizedQuery = (q == null || q.isBlank()) ? null : q.trim();
        return repository.search(normalizedQuery, status, pageable).map(PatientResponse::from);
    }

    // -----------------------------------------------------------------
    // READ — get by id
    // -----------------------------------------------------------------
    @Transactional(readOnly = true)
    @SuppressWarnings("all")
    public PatientResponse get(UUID id, JwtPrincipal principal) {
        requireAuthenticated(principal);

        Patient patient = repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Patient", id));

        // IDOR protection — 404 instead of 403, so existence is not leaked
        if (principal.isPatient() && !principal.isLinkedToPatient(id)) {
            throw ResourceNotFoundException.of("Patient", id);
        }

        return PatientResponse.from(patient);
    }

    // -----------------------------------------------------------------
    // UPDATE
    // -----------------------------------------------------------------
    @Transactional
    @SuppressWarnings("all")
    public PatientResponse update(UUID id, PatientRequest request, JwtPrincipal principal) {
        requireStaff(principal);

        Patient patient = repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Patient", id));

        applyEditableFields(patient, request);

        Patient saved = repository.save(patient);

        auditLogService.record(principal, AuditAction.UPDATE_EMR,
                ResourceType.PATIENT_EMR, id.toString(),
                "Patient record updated", null);

        log.info("Patient updated id={}", id);
        return PatientResponse.from(saved);
    }

    // -----------------------------------------------------------------
    // DEACTIVATE (soft delete) — ADMIN only
    // -----------------------------------------------------------------
    @Transactional
    @SuppressWarnings("all")
    public void deactivate(UUID id, JwtPrincipal principal) {
        if (principal == null || !principal.isAdmin()) {
            throw new AuthorizationException("ADMIN role required to deactivate patients");
        }

        Patient patient = repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Patient", id));

        patient.getStatus().requireTransitionTo(PatientStatus.INACTIVE);
        patient.setStatus(PatientStatus.INACTIVE);
        repository.save(patient);

        auditLogService.record(principal, AuditAction.DELETE_PATIENT,
                ResourceType.PATIENT_EMR, id.toString(),
                "Patient record deactivated", null);

        log.info("Patient deactivated id={}", id);
    }

    // -----------------------------------------------------------------
    // SUMMARY
    // -----------------------------------------------------------------
    @Transactional(readOnly = true)
    @SuppressWarnings("all")
    public Map<String, Object> getSummary(JwtPrincipal principal) {
        requireStaff(principal);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalPatients", repository.count());
        summary.put("activePatients", repository.countByStatus(PatientStatus.ACTIVE));
        summary.put("dormantPatients", repository.countByStatus(PatientStatus.DORMANT));
        summary.put("dischargedPatients", repository.countByStatus(PatientStatus.DISCHARGED));
        summary.put("deceasedPatients", repository.countByStatus(PatientStatus.DECEASED));
        return summary;
    }

    @SuppressWarnings("all")
    public Map<String, Object> getSummary() {
        return getSummary(null);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    @SuppressWarnings("all")
    private void applyEditableFields(Patient patient, PatientRequest request) {
        patient.setFirstName(request.getFirstName());
        patient.setLastName(request.getLastName());
        patient.setDateOfBirth(request.getDateOfBirth());
        patient.setGender(request.getGender());
        patient.setBloodGroup(request.getBloodGroup() == null ? null
                : com.apexcare.patient.entity.BloodGroup.valueOf(
                        request.getBloodGroup().replace("+", "_POSITIVE").replace("-", "_NEGATIVE")));
        patient.setPhone(request.getPhone());
        patient.setEmail(request.getEmail());
        patient.setAddress(request.getAddress());
        patient.setEmergencyContact(request.getEmergencyContact());
        patient.setMedicalHistory(request.getMedicalHistory());
        patient.setAllergies(request.getAllergies());
    }

    @SuppressWarnings("all")
    private void assignUniqueId(Patient patient) {
        if (patient.getUniquePatientId() == null || patient.getUniquePatientId().isBlank()) {
            patient.setUniquePatientId(uniquePatientIdService.nextId());
        }
    }

    @SuppressWarnings("all")
    private static void requireAuthenticated(JwtPrincipal principal) {
        if (principal == null) {
            throw new AuthorizationException("Authentication required");
        }
    }

    @SuppressWarnings("all")
    private static void requireStaff(JwtPrincipal principal) {
        requireAuthenticated(principal);
        if (principal.isPatient()) {
            throw new AuthorizationException("Insufficient role to perform this operation");
        }
    }
}