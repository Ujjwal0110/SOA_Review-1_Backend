package com.apexcare.auth.controller;

import com.apexcare.auth.dto.ApiResponse;
import com.apexcare.auth.dto.AuthTokenResponse;
import com.apexcare.auth.dto.CaptchaResponse;
import com.apexcare.auth.dto.ForgotPasswordRequest;
import com.apexcare.auth.dto.LoginRequest;
import com.apexcare.auth.dto.RegisterRequest;
import com.apexcare.auth.dto.UserResponse;
import com.apexcare.auth.exception.AuthorizationException;
import com.apexcare.auth.security.JwtPrincipal;
import com.apexcare.auth.service.AuthService;
import com.apexcare.auth.service.CaptchaService;
import com.apexcare.auth.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final CaptchaService captchaService;

    public AuthController(AuthService authService,
                          UserService userService,
                          CaptchaService captchaService) {
        this.authService = authService;
        this.userService = userService;
        this.captchaService = captchaService;
    }

    // -----------------------------------------------------------------
    // PUBLIC endpoints — no JWT required
    // -----------------------------------------------------------------

    @GetMapping("/captcha")
    @Operation(summary = "Generate a fresh visual security CAPTCHA challenge")
    public ResponseEntity<ApiResponse<CaptchaResponse>> getCaptcha() {
        CaptchaResponse response = captchaService.generateCaptcha();
        return ResponseEntity.ok(ApiResponse.ok("Captcha generated successfully", response));
    }

    /**
     * Public self-registration with a chosen role (patient, doctor, nurse,
     * receptionist, billing admin, or administrator).
     */
    @PostMapping("/register")
    @Operation(
            summary = "Register a new user account",
            description = "Public self-registration. Supply a role so the account "
                    + "is created with the matching dashboard access."
    )
    public ResponseEntity<ApiResponse<AuthTokenResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        AuthTokenResponse token = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Account created successfully", token));
    }

    @PostMapping("/login")
    @Operation(
            summary = "Login and receive a JWT",
            description = "Returns a Bearer token. If CAPTCHA is enabled, "
                    + "supply the captcha ID and answer."
    )
    public ResponseEntity<ApiResponse<AuthTokenResponse>> login(
            @Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Login successful", authService.login(request)));
    }

    @PostMapping("/forgot-password")
    @Operation(
            summary = "Request a password reset",
            description = "Always returns the same response, whether or not the email exists — "
                    + "to prevent account enumeration."
    )
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        // Do not reveal whether the email exists
        return ResponseEntity.ok(ApiResponse.ok(
                "If the email exists, a reset link will be sent", null));
    }

    // -----------------------------------------------------------------
    // PROTECTED endpoints — JWT required
    // -----------------------------------------------------------------

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get the current authenticated user's profile")
    @SuppressWarnings("null")
    public ResponseEntity<ApiResponse<UserResponse>> me(
            @AuthenticationPrincipal JwtPrincipal principal) {

        if (principal == null) {
            // Should never happen if SecurityConfig is correct — defence in depth
            throw new AuthorizationException("Authentication required");
        }

        return ResponseEntity.ok(ApiResponse.ok(
                "Profile loaded",
                userService.findById(principal.userId())));
    }
}