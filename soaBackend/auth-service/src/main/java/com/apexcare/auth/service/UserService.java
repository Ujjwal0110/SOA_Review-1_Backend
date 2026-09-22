package com.apexcare.auth.service;

import com.apexcare.auth.dto.UpdateUserRequest;
import com.apexcare.auth.dto.UserResponse;
import com.apexcare.auth.entity.Role;
import com.apexcare.auth.entity.User;
import com.apexcare.auth.exception.AuthorizationException;
import com.apexcare.auth.exception.InvalidRequestException;
import com.apexcare.auth.exception.ResourceNotFoundException;
import com.apexcare.auth.repository.UserRepository;
import com.apexcare.auth.security.JwtPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // -----------------------------------------------------------------
    // READ
    // -----------------------------------------------------------------
    @Transactional(readOnly = true)
    @SuppressWarnings("all")
    public Page<UserResponse> findAll(@NonNull Pageable pageable, JwtPrincipal principal) {
        requireAdmin(principal);
        return userRepository.findAll(pageable).map(UserResponse::from);
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("all")
    public UserResponse findById(@NonNull UUID id) {
        return UserResponse.from(get(id));
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("all")
    public List<UserResponse> findDoctors() {
        return userRepository.findByRoleAndEnabledTrue(Role.DOCTOR).stream()
                .map(UserResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("all")
    public UserResponse findDoctor(@NonNull UUID id) {
        User user = get(id);
        // Do not leak that a non-doctor user exists with this ID
        if (user.getRole() != Role.DOCTOR || !user.isEnabled()) {
            throw ResourceNotFoundException.of("Doctor", id);
        }
        return UserResponse.from(user);
    }

    // -----------------------------------------------------------------
    // UPDATE — profile fields only (no role, no enabled, no linkedPatientId)
    // -----------------------------------------------------------------
    /**
     * Updates mutable profile fields of a user.
     *
     * <p>Role, enabled status, and linkedPatientId are <b>deliberately</b> not
     * updatable here — they have their own dedicated endpoints with stricter
     * authorization and audit requirements.
     */
    @Transactional
    @SuppressWarnings("all")
    public UserResponse update(UUID id,
                               UpdateUserRequest request,
                               JwtPrincipal principal) {
        requireAdmin(principal);

        User user = get(id);

        // Email — normalize + uniqueness check with a different id
        if (StringUtils.hasText(request.getEmail())) {
            String email = request.getEmail().trim().toLowerCase();
            if (!email.equalsIgnoreCase(user.getEmail())
                    && userRepository.existsByEmailIgnoreCase(email)) {
                throw new InvalidRequestException("Email is already registered");
            }
            user.setEmail(email);
        }

        if (StringUtils.hasText(request.getFirstName())) {
            user.setFirstName(request.getFirstName().trim());
        }
        if (StringUtils.hasText(request.getLastName())) {
            user.setLastName(request.getLastName().trim());
        }

        User saved = userRepository.save(user);

        log.info("User profile updated userId={} by={}",
                saved.getId(), principal != null ? principal.userId() : "system");

        return UserResponse.from(saved);
    }

    // -----------------------------------------------------------------
    // HELPERS
    // -----------------------------------------------------------------
    @SuppressWarnings("all")
    private User get(@NonNull UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("User", id));
    }

    @SuppressWarnings("all")
    private static void requireAdmin(JwtPrincipal principal) {
        if (principal == null || !principal.hasRole(Role.ADMIN)) {
            throw new AuthorizationException("ADMIN role required");
        }
    }
}