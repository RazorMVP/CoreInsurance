package com.nubeero.cia.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC-slice coverage for the two 404-producing branches of
 * {@link GlobalExceptionHandler} added in Session 115. Backlog row
 * {@code E1-test}.
 *
 * <p>Design — uses a fake controller that explicitly throws each
 * exception rather than coaxing Spring's path-matching into producing
 * them organically. {@code @ExceptionHandler} resolution is type-based,
 * not dispatch-source-based, so a hand-thrown {@link NoHandlerFoundException}
 * routes through the same advice as one the dispatcher servlet would
 * produce for an unmatched {@code @RequestMapping}. This keeps the test
 * deterministic across the Spring 6.0 → 6.1 NoResourceFoundException
 * split: regardless of which exception the framework defaults to for
 * unmapped paths in a given Boot version, both handler branches are
 * exercised.
 *
 * <p>{@code addFilters = false} skips the security filter chain — the
 * test focuses on advice routing, not auth. cia-common pulls in
 * spring-boot-starter-oauth2-resource-server which would otherwise
 * demand a JwtDecoder bean.
 */
@WebMvcTest(controllers = GlobalExceptionHandlerMvcTest.FakeController.class)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerMvcTest.FakeController.class})
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerMvcTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("NoHandlerFoundException → 404 with ApiResponse.error(NOT_FOUND, ...)")
    void noHandlerBranch() throws Exception {
        mvc.perform(get("/test/throw-no-handler"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value(containsString("/intentional-unmapped")));
    }

    @Test
    @DisplayName("NoResourceFoundException → 404 with ApiResponse.error(NOT_FOUND, ...)")
    void noResourceBranch() throws Exception {
        mvc.perform(get("/test/throw-no-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value(containsString("intentional-missing.html")));
    }

    @Test
    @DisplayName("genuinely unmapped path → 404 (regardless of which 404-class the framework picks)")
    void genuinelyUnmappedPath() throws Exception {
        // No @GetMapping covers this path; whichever exception Boot's
        // dispatcher throws (NoHandlerFoundException pre-3.2, generally
        // NoResourceFoundException post-3.2) both route to the same
        // ApiResponse.error("NOT_FOUND", ...) shape. The assertion is
        // intentionally generic so a future Spring upgrade that
        // re-routes the default doesn't break this test.
        mvc.perform(get("/this-path-does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
    }

    @RestController
    static class FakeController {

        @GetMapping("/test/throw-no-handler")
        void throwNoHandler() throws NoHandlerFoundException {
            throw new NoHandlerFoundException("GET", "/intentional-unmapped", new HttpHeaders());
        }

        @GetMapping("/test/throw-no-resource")
        void throwNoResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "intentional-missing.html");
        }
    }

    /**
     * Bootstrap fixture for {@link WebMvcTest}. cia-common is a library
     * module with no {@code @SpringBootApplication} on the production
     * classpath, so the slice's upward {@code @SpringBootConfiguration}
     * search would otherwise fail. The fixture is intentionally empty
     * — {@code @EnableAutoConfiguration} lets the web slice pick up
     * Jackson + MessageConverters; the controller and advice are wired
     * via the {@code controllers = ...} + {@code @Import} pair above.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
    }
}
