package com.apexcare.billing.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/billing/generate", "/api/billing/from-appointment").permitAll() // Called by Appointment Service or authenticated staff
                        .requestMatchers(HttpMethod.GET, "/api/billing/stats/**").hasAnyRole("ADMIN", "BILLING_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/billing/*/pay").hasAnyRole("ADMIN", "BILLING_ADMIN", "PATIENT", "RECEPTIONIST", "DOCTOR")
                        .requestMatchers(HttpMethod.GET, "/api/billing/*/receipt").hasAnyRole("ADMIN", "BILLING_ADMIN", "PATIENT", "RECEPTIONIST", "DOCTOR")
                        .requestMatchers(HttpMethod.GET, "/api/billing/patient/**").hasAnyRole("ADMIN", "BILLING_ADMIN", "PATIENT", "RECEPTIONIST", "DOCTOR")
                        .requestMatchers(HttpMethod.GET, "/api/billing/appointment/**").hasAnyRole("ADMIN", "BILLING_ADMIN", "PATIENT", "RECEPTIONIST", "DOCTOR")
                        .requestMatchers(HttpMethod.POST, "/api/billing").hasAnyRole("ADMIN", "BILLING_ADMIN", "RECEPTIONIST")
                        .requestMatchers(HttpMethod.GET, "/api/billing/**").hasAnyRole("ADMIN", "BILLING_ADMIN", "RECEPTIONIST", "DOCTOR", "PATIENT")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
