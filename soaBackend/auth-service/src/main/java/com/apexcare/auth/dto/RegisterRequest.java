package com.apexcare.auth.dto;

import com.apexcare.auth.entity.Role;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Public self-registration payload. Callers choose an account {@link Role}
 * (patient, doctor, nurse, receptionist, billing admin, or administrator).
 * {@code SYSTEM} accounts cannot be created through this endpoint.
 */
public class RegisterRequest {

    @NotBlank
    @Size(min = 3, max = 80)
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$",
             message = "Username may only contain letters, digits, '.', '_', '-'")
    private String username;

    @NotBlank
    @Email
    @Size(max = 160)
    private String email;

    @NotBlank
    @Size(min = 8, max = 72)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    @NotBlank
    @Size(max = 80)
    private String firstName;

    @NotBlank
    @Size(max = 80)
    private String lastName;

    @NotNull(message = "Role is required")
    private Role role;

    @Size(max = 32)
    private String doctorCode;

    @Size(max = 64)
    private String captchaId;

    @Size(max = 16)
    private String captchaAnswer;

    // ---- getters / setters ----

    public String getUsername() { return username; }
    public void setUsername(String username) {
        this.username = username == null ? null : username.trim().toLowerCase();
    }

    public String getEmail() { return email; }
    public void setEmail(String email) {
        this.email = email == null ? null : email.trim().toLowerCase();
    }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) {
        this.firstName = firstName == null ? null : firstName.trim();
    }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) {
        this.lastName = lastName == null ? null : lastName.trim();
    }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public String getDoctorCode() { return doctorCode; }
    public void setDoctorCode(String doctorCode) {
        this.doctorCode = doctorCode == null || doctorCode.isBlank()
                ? null
                : doctorCode.trim().toUpperCase();
    }

    public String getCaptchaId() { return captchaId; }
    public void setCaptchaId(String captchaId) { this.captchaId = captchaId; }

    public String getCaptchaAnswer() { return captchaAnswer; }
    public void setCaptchaAnswer(String captchaAnswer) {
        this.captchaAnswer = captchaAnswer == null ? null : captchaAnswer.trim();
    }

    @Override
    public String toString() {
        return "RegisterRequest{username='" + username + "', password='***'}";
    }
}