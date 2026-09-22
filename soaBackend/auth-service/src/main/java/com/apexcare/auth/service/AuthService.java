package com.apexcare.auth.service;

import com.apexcare.auth.dto.AuthTokenResponse;
import com.apexcare.auth.dto.ForgotPasswordRequest;
import com.apexcare.auth.dto.LoginRequest;
import com.apexcare.auth.dto.RegisterRequest;
import com.apexcare.auth.entity.Role;
import com.apexcare.auth.entity.User;
import com.apexcare.auth.exception.InvalidCredentialsException;
import com.apexcare.auth.exception.InvalidRequestException;
import com.apexcare.auth.repository.UserRepository;
import com.apexcare.auth.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * Constant hash used to equalize timing when the username does not exist.
     * Prevents username enumeration via response-time side channel.
     * The value itself is irrelevant — it just needs to be a valid BCrypt hash.
     */
    private static final String DUMMY_HASH =
            "$2a$10$abcdefghijklmnopqrstuvABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private static final Set<Role> SELF_REGISTER_ROLES = EnumSet.of(
            Role.PATIENT,
            Role.DOCTOR,
            Role.NURSE,
            Role.RECEPTIONIST,
            Role.BILLING_ADMIN,
            Role.ADMIN);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CaptchaService captchaService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       CaptchaService captchaService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.captchaService = captchaService;
    }

    // -----------------------------------------------------------------
    // REGISTER — role-based public self-registration
    // -----------------------------------------------------------------
    /**
     * Public self-registration. The caller selects a {@link Role} from the
     * allowed set (patient, clinical staff, billing, or administrator).
     * Service accounts ({@link Role#SYSTEM}) cannot be created here.
     */
    @Transactional
    public AuthTokenResponse register(RegisterRequest request) {

        if (StringUtils.hasText(request.getCaptchaId())
                && !captchaService.validateCaptcha(request.getCaptchaId(),
                                                    request.getCaptchaAnswer())) {
            throw new InvalidRequestException(
                    "Invalid or expired security CAPTCHA. Please try again.");
        }

        String username = request.getUsername().trim().toLowerCase();
        String email    = request.getEmail().trim().toLowerCase();

        // Case-insensitive uniqueness check BEFORE saving (input is normalized).
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new InvalidRequestException("Username is already taken");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new InvalidRequestException("Email is already registered");
        }

        Role role = request.getRole() == null ? Role.PATIENT : request.getRole();
        if (!SELF_REGISTER_ROLES.contains(role)) {
            throw new InvalidRequestException(
                    "This role cannot be used for self-registration");
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setRole(role);

        if (role == Role.PATIENT) {
            user.setLinkedPatientId(UUID.randomUUID());
        }
        if (role == Role.DOCTOR) {
            user.setDoctorCode(allocateDoctorCode(request.getDoctorCode()));
        }

        user.setEnabled(true);

        User saved = userRepository.save(user);

        log.info("User registered username={} userId={} role={}",
                saved.getUsername(), saved.getId(), saved.getRole());

        return toToken(saved);
    }

    private String allocateDoctorCode(String requested) {
        if (StringUtils.hasText(requested)) {
            String code = requested.trim().toUpperCase();
            if (userRepository.existsByDoctorCode(code)) {
                throw new InvalidRequestException("Doctor code is already in use");
            }
            return code;
        }
        String generated;
        do {
            generated = "D" + UUID.randomUUID().toString()
                    .replace("-", "")
                    .substring(0, 7)
                    .toUpperCase();
        } while (userRepository.existsByDoctorCode(generated));
        return generated;
    }

    // -----------------------------------------------------------------
    // LOGIN
    // -----------------------------------------------------------------
    @Transactional(readOnly = true)
    public AuthTokenResponse login(LoginRequest request) {

        if (StringUtils.hasText(request.getCaptchaId())
                && !captchaService.validateCaptcha(request.getCaptchaId(),
                                                    request.getCaptchaAnswer())) {
            throw InvalidCredentialsException.badCredentials();
        }

        String username = request.getUsername() == null
                ? ""
                : request.getUsername().trim().toLowerCase();

        User user = userRepository.findByUsernameIgnoreCase(username).orElse(null);

        if (user == null) {
            // Perform a dummy hash to equalize response timing (anti-enumeration).
            passwordEncoder.matches(request.getPassword(), DUMMY_HASH);
            log.warn("Login failed (unknown username) username={}", username);
            throw InvalidCredentialsException.badCredentials();
        }

        boolean passwordMatches =
                passwordEncoder.matches(request.getPassword(), user.getPasswordHash());

        if (!user.isEnabled() || !passwordMatches) {
            log.warn("Login failed username={} enabled={} reason={}",
                    username, user.isEnabled(),
                    !user.isEnabled() ? "disabled" : "bad_password");
            throw InvalidCredentialsException.badCredentials();
        }

        user.recordLogin();
        // user is managed by the persistence context — no explicit save needed

        log.info("Login success username={} role={}", user.getUsername(), user.getRole());

        return toToken(user);
    }

    // -----------------------------------------------------------------
    // FORGOT PASSWORD — no user enumeration
    // -----------------------------------------------------------------
    @Transactional(readOnly = true)
    public void forgotPassword(ForgotPasswordRequest request) {
        if (request.getEmail() == null) {
            return;   // silently succeed
        }
        String email = request.getEmail().trim().toLowerCase();

        userRepository.findByEmailIgnoreCase(email)
                .ifPresent(user -> log.info(
                        "Password reset requested userId={} (delivery is simulated)",
                        user.getId()));

        // Deliberately do not reveal whether the email exists.
    }

    // -----------------------------------------------------------------
    // TOKEN BUILDER
    // -----------------------------------------------------------------
    private AuthTokenResponse toToken(User user) {
        AuthTokenResponse response = new AuthTokenResponse();

        response.setToken(jwtService.generateToken(
                user.getId(),
                user.getUsername(),
                user.getRole(),
                user.getLinkedPatientId(),
                user.getDoctorCode()));

        response.setExpiresAt(jwtService.expirationInstant());
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setRole(user.getRole());
        response.setLinkedPatientId(user.getLinkedPatientId());
        response.setDoctorCode(user.getDoctorCode());
        response.setFirstName(user.getFirstName());
        response.setLastName(user.getLastName());
        return response;
    }
}