package com.usedlog.auth;

import java.time.Clock;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.usedlog.auth.AuthDtos.RegisterRequest;
import com.usedlog.common.Text;
import com.usedlog.user.AppUser;
import com.usedlog.user.AppUserRepository;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public AppUser register(RegisterRequest request) {
        String email = Text.normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists.");
        }
        AppUser user = new AppUser(
                email,
                Text.clean(request.displayName()),
                passwordEncoder.encode(request.password()),
                clock.instant());
        return users.save(user);
    }
}
