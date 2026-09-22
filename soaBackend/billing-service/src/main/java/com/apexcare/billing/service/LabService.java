package com.apexcare.billing.service;

import com.apexcare.billing.entity.LabTestOrder;
import com.apexcare.billing.repository.LabTestOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.apexcare.billing.controller.LabController.CreateLabOrderRequest;
import com.apexcare.billing.controller.LabController.RecordLabResultRequest;
import com.apexcare.billing.security.JwtPrincipal;

@Service
public class LabService {

    private final LabTestOrderRepository repository;

    public LabService(LabTestOrderRepository repository) {
        this.repository = repository;
    }

    public List<LabTestOrder> getAllOrders() {
        return repository.findAll();
    }

    public List<LabTestOrder> getPatientOrders(UUID patientId) {
        return repository.findByPatientIdOrderByOrderedAtDesc(patientId);
    }

    public Page<LabTestOrder> getAllOrders(Pageable pageable, JwtPrincipal principal) {
        return repository.findAll(pageable);
    }

    public List<LabTestOrder> getPatientOrders(UUID patientId, JwtPrincipal principal) {
        if (principal != null && principal.isPatient() && !principal.isLinkedToPatient(patientId)) {
            throw new SecurityException("Patients may only access their own orders");
        }
        return getPatientOrders(patientId);
    }

    public LabTestOrder getOrderById(UUID id, JwtPrincipal principal) {
        LabTestOrder order = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Lab test order not found: " + id));
        if (principal != null && principal.isPatient() && !principal.isLinkedToPatient(order.getPatientId())) {
            throw new SecurityException("Patients may only access their own orders");
        }
        return order;
    }

    @Transactional
    public LabTestOrder createOrder(CreateLabOrderRequest request, JwtPrincipal principal) {
        LabTestOrder order = new LabTestOrder();
        order.setPatientId(request.patientId());
        order.setDoctorId(request.doctorId());
        order.setAppointmentId(request.appointmentId());
        order.setTestName(request.testName());
        order.setCategory(request.category());
        order.setPriority(request.priority());
        order.setOrderedBy(principal == null ? null : principal.userId());
        return createOrder(order);
    }

    @Transactional
    public LabTestOrder recordResult(UUID id, RecordLabResultRequest request, JwtPrincipal principal) {
        return updateResult(id, request.resultSummary(), request.referenceRange(),
                request.abnormalFlag(), request.resultFileUrl(), principal);
    }

    @Transactional
    public LabTestOrder createOrder(LabTestOrder order) {
        if (order.getStatus() == null) order.setStatus("ORDERED");
        return repository.save(order);
    }

    @Transactional
    @SuppressWarnings("null")
    public LabTestOrder updateResult(UUID id, String resultSummary, String referenceRange, String abnormalFlag) {
        return updateResult(id, resultSummary, referenceRange, abnormalFlag, null, null);
    }

    @Transactional
    public LabTestOrder updateResult(UUID id, String resultSummary, String referenceRange,
                                     String abnormalFlag, String resultFileUrl, JwtPrincipal principal) {
        LabTestOrder order = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Lab test order not found: " + id));
        order.setResultSummary(resultSummary);
        order.setReferenceRange(referenceRange);
        order.setAbnormalFlag(abnormalFlag != null ? abnormalFlag : "NORMAL");
        order.setResultFileUrl(resultFileUrl);
        order.setStatus("COMPLETED");
        order.setCompletedAt(Instant.now());
        return repository.save(order);
    }
}
