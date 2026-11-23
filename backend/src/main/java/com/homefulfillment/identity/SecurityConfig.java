package com.homefulfillment.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefulfillment.web.ApiProblem;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration
class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean SecurityContextRepository contextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean CookieCsrfTokenRepository csrfRepository() { return new CookieCsrfTokenRepository(); }

    @Bean AuthenticationManager authenticationManager(Identity identity, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(username -> {
            Identity.Account account = identity.account(username);
            if (account == null) throw new UsernameNotFoundException("Unknown account");
            return User.withUsername(account.username()).password(account.hash())
                    .roles(account.role()).disabled(!account.enabled()).build();
        });
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean SecurityFilterChain security(HttpSecurity http, SecurityContextRepository context,
            CookieCsrfTokenRepository csrf, ObjectMapper json) throws Exception {
        return http
                .securityContext(c -> c.securityContextRepository(context))
                .requestCache(c -> c.disable())
                .csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/readiness", "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req,res,cause) ->
                                new ApiProblem(401,"UNAUTHENTICATED","请登录").write(req,res,json))
                        .accessDeniedHandler((req,res,cause) -> ApiProblem.forbidden().write(req,res,json)))
                .logout(l -> l.logoutUrl("/api/v1/auth/logout").deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((req,res,auth) -> res.setStatus(204)))
                .build();
    }
}
