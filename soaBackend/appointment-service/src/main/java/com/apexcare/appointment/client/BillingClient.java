package com.apexcare.appointment.client;

import com.apexcare.appointment.dto.ApiResponse;
import com.apexcare.appointment.dto.GenerateBillRequest;
import com.apexcare.appointment.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(
        name = "billing-service",
        path = "/api/billing",
        fallbackFactory = BillingClient.BillingClientFallbackFactory.class,
        configuration = BillingClient.FeignConfig.class,
        url = "${billing-service.url:http://localhost:8084}"
)
public interface BillingClient {

    default ApiResponse<Object> generateFromAppointment(GenerateBillRequest request) {
        return generateFromAppointment(request, null);
    }

    @PostMapping("/from-appointment")
    ApiResponse<Object> generateFromAppointment(
            @RequestBody GenerateBillRequest request,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization
    );

    // -----------------------------------------------------------------
    // Fallback: fail loud. Billing must be reachable for a booking to
    // be considered complete; do not silently skip billing.
    // -----------------------------------------------------------------
    @Component
    class BillingClientFallbackFactory implements FallbackFactory<BillingClient> {

        private static final Logger log =
                LoggerFactory.getLogger(BillingClientFallbackFactory.class);

        @Override
        public BillingClient create(Throwable cause) {
            return (request, authorization) -> {
                log.error("Billing service unavailable for request {}: {}",
                        request, cause.getMessage(), cause);
                throw new ServiceUnavailableException("Billing Service is unavailable");
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