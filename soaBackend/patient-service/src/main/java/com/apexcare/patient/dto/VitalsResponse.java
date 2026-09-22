package com.apexcare.patient.dto;

import com.apexcare.patient.entity.PatientVitals;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record VitalsResponse(UUID id, UUID patientId, Instant recordedAt, UUID recordedBy,
        Integer heartRate, Integer systolicBp, Integer diastolicBp, Integer spO2,
        Integer respiratoryRate, BigDecimal temperature, String spo2Scale,
        String consciousness, boolean onSupplementalOxygen, Integer news2Score,
        String riskLevel, String clinicalAlert, boolean singleParamCritical) {
    public static VitalsResponse from(PatientVitals v) {
        return new VitalsResponse(v.getId(), v.getPatientId(), v.getRecordedAt(), v.getRecordedBy(),
                v.getHeartRate(), v.getSystolicBp(), v.getDiastolicBp(), v.getSpO2(),
                v.getRespiratoryRate(), v.getTemperature(),
                v.getSpo2Scale() == null ? null : v.getSpo2Scale().name(),
                v.getConsciousness() == null ? null : v.getConsciousness().name(),
                v.isOnSupplementalOxygen(), v.getNews2Score(),
                v.getRiskLevel() == null ? null : v.getRiskLevel().name(),
                v.getClinicalAlert(), v.isSingleParamCritical());
    }
}
