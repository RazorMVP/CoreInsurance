package com.nubeero.cia.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SensitiveEndpointRateLimitFilter extends OncePerRequestFilter {

    private final SensitiveEndpointRateLimitPolicy policy;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public SensitiveEndpointRateLimitFilter(SensitiveEndpointRateLimitPolicy policy) {
        this(policy, Clock.systemUTC());
    }

    SensitiveEndpointRateLimitFilter(SensitiveEndpointRateLimitPolicy policy, Clock clock) {
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var rule = policy.match(request);
        if (rule.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = rule.get().id() + ":" + clientKey(request);
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> Bucket.full(rule.get(), clock.millis()));
        if (!bucket.tryConsume(rule.get(), clock.millis())) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"rate_limit_exceeded\",\"message\":\"Too many requests\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String clientKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getName() != null && !authentication.getName().isBlank()) {
            return "principal:" + authentication.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private static final class Bucket {
        private int tokens;
        private long resetAtMillis;

        private Bucket(int tokens, long resetAtMillis) {
            this.tokens = tokens;
            this.resetAtMillis = resetAtMillis;
        }

        static Bucket full(SensitiveEndpointRateLimitPolicy.Rule rule, long nowMillis) {
            return new Bucket(rule.capacity(), nowMillis + rule.window().toMillis());
        }

        synchronized boolean tryConsume(SensitiveEndpointRateLimitPolicy.Rule rule, long nowMillis) {
            if (nowMillis >= resetAtMillis) {
                tokens = rule.capacity();
                resetAtMillis = nowMillis + rule.window().toMillis();
            }
            if (tokens <= 0) {
                return false;
            }
            tokens--;
            return true;
        }
    }
}
