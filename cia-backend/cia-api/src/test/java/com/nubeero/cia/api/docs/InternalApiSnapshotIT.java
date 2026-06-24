package com.nubeero.cia.api.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.api.finance.FinanceWebItSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Drift guard + regenerator for the committed internal OpenAPI snapshot
 * {@code docs-site/static/internal-api.json} (served as the staff/system API
 * reference). The file is a hand-committed snapshot of Springdoc's live
 * {@code internal-api} group; before this guard nothing regenerated or validated
 * it on endpoint changes, so the SP1 platform-admin endpoints
 * ({@code /api/v1/platform/**}) silently drifted out of it.
 *
 * <h2>Two modes, one test</h2>
 * <ul>
 *   <li><b>Guard (default, runs in CI):</b> boots the full context, fetches the
 *       live {@code internal-api} spec, and asserts its set of
 *       {@code METHOD path} operations equals the committed file's. A new
 *       controller endpoint that isn't in the snapshot fails the build with an
 *       actionable message. The comparison is on the operation <em>set</em>
 *       (semantic), so it is immune to Springdoc key-ordering / formatting drift
 *       that a byte-diff would choke on.</li>
 *   <li><b>Regenerate:</b> run with {@code -Dcia.openapi.snapshot.write=true} to
 *       overwrite the committed file with the live spec, then normalise the
 *       formatting (see the failure message). Used whenever endpoints change.</li>
 * </ul>
 *
 * <p>Extends {@link FinanceWebItSupport} deliberately: that base's
 * {@code @SpringBootTest} context (full controller scan + Temporal/JwtDecoder/
 * storage mocks) is already booted and Spring-cached by the finance ITs, so this
 * guard reuses it at no extra boot cost. It adds no context config of its own
 * (only a test method), so the cache key is unchanged.
 */
class InternalApiSnapshotIT extends FinanceWebItSupport {

    /** Springdoc serves the {@code internal-api} group here (permitAll in SecurityConfig). */
    private static final String INTERNAL_API_DOCS = "/partner/v3/api-docs/internal-api";

    /** System property that flips this test from guard mode to regenerate mode. */
    private static final String WRITE_FLAG = "cia.openapi.snapshot.write";

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "post", "put", "patch", "delete", "head", "options");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void internalApiSnapshotMatchesLiveControllers() throws Exception {
        String body = mockMvc.perform(get(INTERNAL_API_DOCS))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode live = objectMapper.readTree(body);
        Path specFile = committedSpecFile();

        if (Boolean.getBoolean(WRITE_FLAG)) {
            Files.writeString(specFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(live));
            System.out.println("[internal-api-snapshot] regenerated " + specFile
                    + " (" + operations(live).size() + " operations). "
                    + "Normalise formatting with: python3 -m json.tool --indent 2 " + specFile + " <out>");
            return;
        }

        assertThat(Files.exists(specFile))
                .as("committed snapshot not found at %s", specFile).isTrue();
        JsonNode committed = objectMapper.readTree(Files.readString(specFile));

        Set<String> liveOps = operations(live);
        Set<String> committedOps = operations(committed);

        Set<String> inCodeNotInDoc = new TreeSet<>(liveOps);
        inCodeNotInDoc.removeAll(committedOps);
        Set<String> inDocNotInCode = new TreeSet<>(committedOps);
        inDocNotInCode.removeAll(liveOps);

        String regenHint = " — regenerate with `mvn -pl cia-api failsafe:integration-test "
                + "-Dit.test=InternalApiSnapshotIT -D" + WRITE_FLAG + "=true` then "
                + "`python3 -m json.tool --indent 2` the file.";
        assertThat(inCodeNotInDoc)
                .as("endpoints exist in code but are MISSING from docs-site/static/internal-api.json"
                        + regenHint)
                .isEmpty();
        assertThat(inDocNotInCode)
                .as("endpoints in docs-site/static/internal-api.json no longer exist in code"
                        + regenHint)
                .isEmpty();
    }

    /** The set of {@code "METHOD /path"} operations declared in an OpenAPI doc. */
    private Set<String> operations(JsonNode spec) {
        Set<String> ops = new TreeSet<>();
        JsonNode paths = spec.path("paths");
        paths.fieldNames().forEachRemaining(path -> {
            JsonNode item = paths.get(path);
            item.fieldNames().forEachRemaining(method -> {
                if (HTTP_METHODS.contains(method)) {
                    ops.add(method.toUpperCase() + " " + path);
                }
            });
        });
        return ops;
    }

    /** {@code docs-site/static/internal-api.json}, resolved from the cia-api module dir (user.dir). */
    private Path committedSpecFile() {
        return Paths.get(System.getProperty("user.dir"))
                .resolve("../../docs-site/static/internal-api.json")
                .normalize();
    }
}
