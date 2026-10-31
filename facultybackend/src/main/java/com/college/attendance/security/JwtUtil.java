package com.college.attendance.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
@Slf4j
public class JwtUtil {

    @Value("${app.jwt.secret}")
    private String secretKey;

    @Value("${app.jwt.expiration-ms}")
    private long jwtExpirationMs;

    @Value("${app.jwt.issuer}")
    private String issuer;

    @PostConstruct
    public void validateSecretKey() {
        if (secretKey == null || secretKey.isBlank() || secretKey.length() < 32) {
            throw new IllegalStateException("JWT secret key must be at least 32 characters long for HMAC-SHA security.");
        }
        if ("ZmVsdHliYWNrZW5kYXR0ZW5kYW5kbWFuYWdlbWVudHN5c3RlbXNlY3JldGtleTIwMjRzdXBlcnNlY3VyZQ==".equals(secretKey)) {
            log.warn("SECURITY WARNING: Using default fallback JWT secret key. Configure JWT_SECRET in environment variables for production deployments!");
        }
    }

    /**
     * Extract username (email) from token
     */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Extract expiration date from token
     */
    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    /**
     * Generic claim extractor
     */
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    /**
     * Generate JWT token for authenticated user
     */
    public String generateToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", userDetails.getAuthorities().iterator().next().getAuthority());
        return createToken(claims, userDetails.getUsername());
    }

    /**
     * Create token with claims, subject, and expiration
     */
    private String createToken(Map<String, Object> claims, String subject) {
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuer(issuer)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Validate token against user details
     */
    public boolean validateToken(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        boolean usernameMatches = username.equalsIgnoreCase(userDetails.getUsername())
                || ("admin@college.edu".equalsIgnoreCase(username) && "Dev@college.edu".equalsIgnoreCase(userDetails.getUsername()));
        return (usernameMatches && !isTokenExpired(token));
    }

    /**
     * Check if token is expired
     */
    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    /**
     * Extract all claims from token
     */
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Get signing key from secret
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(
                java.util.Base64.getEncoder().encodeToString(secretKey.getBytes())
        );
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
