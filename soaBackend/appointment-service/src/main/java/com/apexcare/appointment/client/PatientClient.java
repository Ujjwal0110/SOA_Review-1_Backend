package com.apexcare.appointment.client;

import com.apexcare.appointment.dto.ApiResponse;
import com.apexcare.appointment.dto.PatientRemote;
import com.apexcare.appointment.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;

@FeignClient(
        name = "patient-service",
        path = "/api/patients",
        fallbackFactory = PatientClient.PatientClientFallbackFactory.class,
        configuration = PatientClient.FeignConfig.class,
        url = "${patient-service.url:http://localhost:8082}"
)
public interface PatientClient {

    default ApiResponse<PatientRemote> getPatient(UUID id) {
        return getPatient(id, null);
    }

    @GetMapping("/{id}")
    ApiResponse<PatientRemote> getPatient(
            @PathVariable("id") UUID id,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization
    );

    // -----------------------------------------------------------------
    // Fallback: fail loud. A booking cannot be trusted without verifying
    // the patient exists and is eligible.
    // -----------------------------------------------------------------
    @Component
    class PatientClientFallbackFactory implements FallbackFactory<PatientClient> {

        private static final Logger log =
                LoggerFactory.getLogger(PatientClientFallbackFactory.class);

        @Override
        public PatientClient create(Throwable cause) {
            return (id, authorization) -> {
                log.error("Patient service unavailable while fetching patient id={}: {}",
                        id, cause.getMessage(), cause);
                throw new ServiceUnavailableException(
                        "Patient Service is unavailable. Appointment cannot be validated right now.");
            };
        }
    }

    // -----------------------------------------------------------------
    // Feign tuning: explicit timeouts so requests never hang.
    // -----------------------------------------------------------------
    class FeignConfig {

        @org.springframework.context.annotation.Bean
        public feign.Request.Options feignRequestOptions() {
            return new feign.Request.Options(
                    10_000,   // connect timeout (ms) - increased from 3s
                    30_000,   // read timeout (ms) - increased from 8s
                    true     // follow redirects
            );
        }

        @org.springframework.context.annotation.Bean
        public feign.Logger.Level feignLoggerLevel() {
            return feign.Logger.Level.BASIC;
        }
    }
}