package com.apexcare.patient.dto;

import com.apexcare.patient.entity.ConsciousnessLevel;
import com.apexcare.patient.entity.Spo2Scale;
import java.math.BigDecimal;
import java.util.UUID;

public record RecordVitalsRequest(UUID patientId, Integer heartRate, Integer systolicBp,
        Integer diastolicBp, Integer spO2, Integer respiratoryRate, BigDecimal temperature,
        Spo2Scale spo2Scale, ConsciousnessLevel consciousness, Boolean onSupplementalOxygen) {}
