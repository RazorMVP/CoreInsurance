package com.nubeero.cia.common.exception;

import com.nubeero.cia.common.api.ApiError;
import com.nubeero.cia.common.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CiaException.class)
    public ResponseEntity<ApiResponse<Void>> handleCiaException(CiaException ex) {
        log.warn("Business exception [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity
                .status(ex.getHttpStatus())
                .body(ApiResponse.error(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiError> errors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> {
                    String field = (error instanceof FieldError fe) ? fe.getField() : null;
                    return new ApiError("VALIDATION_ERROR", error.getDefaultMessage(), field);
                })
                .toList();
        return ResponseEntity.badRequest().body(ApiResponse.error(errors));
    }

    /**
     * Unmapped request path → 404. Backlog E1: before this handler, every
     * unmapped path (including Springdoc's bare {@code /v3/api-docs} — which
     * doesn't exist because the path is overridden to {@code /partner/v3/api-docs}
     * in {@code application.yml}) was caught by the {@link Exception} fallback
     * and returned 500 with a generic "unexpected error" body. That blew
     * up routine 404s into pageable incidents and hid the real path-config
     * intent. Spring Boot 3.x throws this exception for unmatched
     * {@code @RequestMapping} routes when {@code spring.mvc.throw-exception-if-no-handler-found}
     * is true (the default since Boot 3.0).
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandler(NoHandlerFoundException ex) {
        log.debug("No handler for {} {}", ex.getHttpMethod(), ex.getRequestURL());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("NOT_FOUND", "No handler for " + ex.getHttpMethod() + " " + ex.getRequestURL()));
    }

    /**
     * Static-resource miss → 404. Spring 6.1 added this exception to
     * distinguish "no controller mapping" (NoHandlerFoundException) from
     * "controller matched but resource not on disk" (NoResourceFoundException).
     * Both are 404 territory; without dedicated handlers the latter falls
     * to the {@link Exception} fallback and 500s.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        log.debug("No static resource for {} {}", ex.getHttpMethod(), ex.getResourcePath());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("NOT_FOUND", "No resource: " + ex.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
