package com.rollercoaster.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CLAIM_SUBJECT = "sub";
    private static final String CLAIM_ROLE = "role";
    private static final String DEFAULT_ROLE = "ROLE_OPERATOR";

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistService tokenBlacklistService;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, TokenBlacklistService tokenBlacklistService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        resolveBearerToken(request).ifPresent(token -> authenticateRequestWithToken(token, request));
        filterChain.doFilter(request, response);
    }

    private Optional<String> resolveBearerToken(HttpServletRequest request) {
        String authorizationHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return token.isBlank() ? Optional.empty() : Optional.of(token);
    }

    private void authenticateRequestWithToken(String token, HttpServletRequest request) {
        if (tokenBlacklistService != null && tokenBlacklistService.isBlacklisted(token)) {
            return;
        }

        if (!jwtTokenProvider.validateToken(token)) {
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            return;
        }

        Map<String, Object> claims = jwtTokenProvider.extractClaims(token);
        String username = (String) claims.get(CLAIM_SUBJECT);
        if (username == null || username.isBlank()) {
            return;
        }

        String role = (String) claims.getOrDefault(CLAIM_ROLE, DEFAULT_ROLE);
        UsernamePasswordAuthenticationToken authentication = buildAuthentication(username, role, request);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private UsernamePasswordAuthenticationToken buildAuthentication(
            String username,
            String role,
            HttpServletRequest request) {

        java.util.List<org.springframework.security.core.GrantedAuthority> authorities = mapRoleToAuthorities(role);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username,
                null,
                authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return authentication;
    }

    private java.util.List<org.springframework.security.core.GrantedAuthority> mapRoleToAuthorities(String role) {
        java.util.List<org.springframework.security.core.GrantedAuthority> authorities = new java.util.ArrayList<>();
        String normalizedRole = (role == null || role.isBlank()) ? DEFAULT_ROLE : role.trim().toUpperCase();
        if (!normalizedRole.startsWith("ROLE_")) {
            normalizedRole = "ROLE_" + normalizedRole;
        }
        authorities.add(new SimpleGrantedAuthority(normalizedRole));

        switch (normalizedRole) {
            case "ROLE_ADMIN":
                authorities.add(new SimpleGrantedAuthority("PERM_REPORT_READ"));
                authorities.add(new SimpleGrantedAuthority("PERM_MODEL_WRITE"));
                authorities.add(new SimpleGrantedAuthority("PERM_MODEL_READ"));
                authorities.add(new SimpleGrantedAuthority("PERM_INGEST_RUN"));
                authorities.add(new SimpleGrantedAuthority("PERM_ADMIN"));
                break;
            case "ROLE_OPERATOR":
            case "ROLE_ANALYST":
                authorities.add(new SimpleGrantedAuthority("PERM_REPORT_READ"));
                authorities.add(new SimpleGrantedAuthority("PERM_MODEL_WRITE"));
                authorities.add(new SimpleGrantedAuthority("PERM_MODEL_READ"));
                authorities.add(new SimpleGrantedAuthority("PERM_INGEST_RUN"));
                break;
            case "ROLE_USER":
            default:
                authorities.add(new SimpleGrantedAuthority("PERM_REPORT_READ"));
                authorities.add(new SimpleGrantedAuthority("PERM_MODEL_READ"));
                authorities.add(new SimpleGrantedAuthority("PERM_MODEL_WRITE"));
                authorities.add(new SimpleGrantedAuthority("PERM_INGEST_RUN"));
                break;
        }
        return authorities;
    }
}
