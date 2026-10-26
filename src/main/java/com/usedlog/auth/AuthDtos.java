package com.usedlog.auth;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.usedlog.security.AppUserPrincipal;

/** Request and response bodies for /api/auth. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 100) String password,
            @NotBlank @Size(max = 60) String displayName) {
    }

    public record LoginRequest(
            @NotBlank String email,
            @NotBlank String password) {
    }

    public record UserResponse(UUID id, String email, String displayName) {

        static UserResponse from(AppUserPrincipal principal) {
            return new UserResponse(principal.getId(), principal.getEmail(), principal.getDisplayName());
        }
    }
}
