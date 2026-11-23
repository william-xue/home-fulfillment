package com.homefulfillment.identity;

import com.homefulfillment.web.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    private final Identity identity;
    private final AuthenticationManager authentication;
    private final SecurityContextRepository sessions;
    private final CookieCsrfTokenRepository csrf;

    AuthController(Identity identity, AuthenticationManager authentication,
                   SecurityContextRepository sessions, CookieCsrfTokenRepository csrf) {
        this.identity = identity;
        this.authentication = authentication;
        this.sessions = sessions;
        this.csrf = csrf;
    }

    record Credentials(@NotBlank @Size(max=64) String username, @NotBlank @Size(max=72) String password) {}
    record Profile(String username, String role, List<String> warehouseCodes) {}

    @GetMapping("/csrf")
    Map<String,String> csrf(CsrfToken token) { return Map.of("token", token.getToken(), "headerName", token.getHeaderName()); }

    @PostMapping("/login")
    Profile login(@Valid @RequestBody Credentials input, HttpServletRequest request, HttpServletResponse response) {
        if (input.password().getBytes(StandardCharsets.UTF_8).length > 72) throw ApiProblem.invalid("密码长度不合法");
        Authentication authenticated;
        try {
            authenticated = authentication.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(input.username().trim(),input.password()));
        } catch (AuthenticationException ex) {
            throw new ApiProblem(401,"UNAUTHENTICATED","账号或密码不正确");
        }
        if (request.getSession(false) != null) request.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        sessions.saveContext(context,request,response);
        csrf.saveToken(null,request,response);
        return me(authenticated);
    }

    @GetMapping("/me")
    Profile me(Authentication auth) {
        var actor = identity.actor(auth);
        return new Profile(actor.username(),actor.role(),actor.warehouses());
    }
}
