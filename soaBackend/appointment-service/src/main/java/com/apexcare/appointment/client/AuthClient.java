package com.apexcare.appointment.client;

import com.apexcare.appointment.dto.ApiResponse;
import com.apexcare.appointment.dto.DoctorRemote;
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
        name = "auth-service",
        path = "/api/auth",
        fallbackFactory = AuthClient.AuthClientFallbackFactory.class,
        configuration = AuthClient.FeignConfig.class,
        url = "${auth-service.url:http://localhost:8081}"
)
public interface AuthClient {

    default ApiResponse<DoctorRemote> getDoctor(UUID id) {
        return getDoctor(id, null);
    }

    @GetMapping("/doctors/{id}")
    ApiResponse<DoctorRemote> getDoctor(
            @PathVariable("id") UUID id,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization
    );

    // -----------------------------------------------------------------
    // Fallback: fail loud — appointment booking cannot proceed without
    // verifying the doctor. Do not silently fabricate a "valid" doctor.
    // -----------------------------------------------------------------
    @Component
    class AuthClientFallbackFactory implements FallbackFactory<AuthClient> {

        private static final Logger log =
                LoggerFactory.getLogger(AuthClientFallbackFactory.class);

        @Override
        public AuthClient create(Throwable cause) {
            return (id, authorization) -> {
                log.error("Auth service unavailable while fetching doctor id={}: {}",
                        id, cause.getMessage(), cause);
                throw new ServiceUnavailableException(
                        "Auth Service is unavailable. Doctor validation cannot be completed.");
            };
        }
    }

    // -----------------------------------------------------------------
    // Feign client tuning: explicit timeouts so requests never hang.
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