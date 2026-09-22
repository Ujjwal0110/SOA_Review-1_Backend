package com.apexcare.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class LoginRequest {

    @NotBlank
    @Size(min = 3, max = 80)
    private String username;

    /**
     * BCrypt has a 72-byte input limit — anything longer is silently truncated
     * by some libraries and wastes CPU. Cap it here.
     */
    @NotBlank
    @Size(min = 8, max = 72)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    @Size(max = 64)
    private String captchaId;

    @Size(max = 16)
    private String captchaAnswer;

    public String getUsername() { return username; }

    public void setUsername(String username) {
        this.username = username == null ? null : username.trim().toLowerCase();
    }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getCaptchaId() { return captchaId; }
    public void setCaptchaId(String captchaId) { this.captchaId = captchaId; }

    public String getCaptchaAnswer() { return captchaAnswer; }
    public void setCaptchaAnswer(String captchaAnswer) {
        this.captchaAnswer = captchaAnswer == null ? null : captchaAnswer.trim();
    }

    /** Prevent password from leaking into logs / toString output. */
    @Override
    public String toString() {
        return "LoginRequest{username='" + username + "', password='***'}";
    }
}