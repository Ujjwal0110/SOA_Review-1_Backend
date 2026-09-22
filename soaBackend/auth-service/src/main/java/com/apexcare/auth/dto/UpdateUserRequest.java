package com.apexcare.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Fields that may be updated through the generic user-update endpoint.
 *
 * <p><b>Security note:</b> {@code role}, {@code enabled}, and
 * {@code linkedPatientId} are intentionally NOT present in this DTO.
 *
 * <ul>
 *   <li>{@code role} — privilege escalation risk. Role changes must go
 *       through a dedicated ADMIN-only endpoint with its own audit trail.</li>
 *   <li>{@code enabled} — account enable/disable must go through a
 *       dedicated ADMIN-only endpoint (with self-disable guard).</li>
 *   <li>{@code linkedPatientId} — patient ↔ account linking must be
 *       verified against patient-service before it is set.</li>
 * </ul>
 *
 * <p>If a client sends {@code "role"} / {@code "enabled"} /
 * {@code "linkedPatientId"} in the JSON body, Jackson will reject the request
 * with a 400 (unknown property). This is intentional — we want the caller
 * to know they're using the wrong endpoint.
 */
public class UpdateUserRequest {

    @Email
    @Size(max = 160)
    private String email;

    @Size(max = 80)
    private String firstName;

    @Size(max = 80)
    private String lastName;

    // ---- getters / setters ----

    public String getEmail() { return email; }
    public void setEmail(String email) {
        this.email = email == null ? null : email.trim().toLowerCase();
    }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) {
        this.firstName = firstName == null ? null : firstName.trim();
    }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) {
        this.lastName = lastName == null ? null : lastName.trim();
    }
}