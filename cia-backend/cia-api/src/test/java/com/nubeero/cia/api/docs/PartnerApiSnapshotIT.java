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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Drift guard + regenerator for the committed <em>Partner</em> OpenAPI snapshots
 * — the sibling of {@link InternalApiSnapshotIT} for the {@code partner-api}
 * Springdoc group. Two files are kept in lock-step with the live spec:
 * <ul>
 *   <li>{@code docs-site/static/openapi.json} — the Partner API reference the
 *       Docusaurus site renders (Redoc, {@code spec.url = /openapi.json});</li>
 *   <li>{@code cia-partner-api/docs/openapi.json} — the input the
 *       {@code openapi-generator-maven-plugin} turns into
 *       {@code cia-partner-api/docs/postman.json} at the {@code package} phase.</li>
 * </ul>
 *
 * <p><b>Why this exists:</b> unlike the internal snapshot, the Partner spec had
 * no guard — so it silently drifted for months (list endpoints changed from a
 * {@code Page} wrapper to a bare array in {@code data}, rate-limit + scope
 * changes landed) while the two committed files still showed the pre-change,
 * partly-empty schemas. This test fails the build the moment the Partner
 * operation set changes without the snapshots being regenerated.
 *
 * <h2>Two modes, one test (identical to {@link InternalApiSnapshotIT})</h2>
 * <ul>
 *   <li><b>Guard (default, CI):</b> boots the full context, fetches the live
 *       {@code partner-api} spec, and asserts its {@code METHOD path} operation
 *       set equals each committed file's. Semantic set comparison — immune to
 *       Springdoc key-ordering / formatting drift.</li>
 *   <li><b>Regenerate:</b> {@code -Dcia.openapi.snapshot.write=true} overwrites
 *       both committed files with the live spec; then normalise formatting
 *       (see the failure message) and run {@code mvn -pl cia-partner-api package}
 *       to regenerate {@code postman.json}.</li>
 * </ul>
 *
 * <p>Extends {@link FinanceWebItSupport} for the same reason the internal guard
 * does: the {@code @SpringBootTest} context is already booted + Spring-cached by
 * the finance ITs, and this test adds no context config of its own, so it reuses
 * that cache at no extra boot cost. {@code OpenApiDocsSmokeIT} already proves the
 * {@code /partner/v3/api-docs/partner-api} endpoint renders in this harness.
 */
class PartnerApiSnapshotIT extends FinanceWebItSupport {

    /** Springdoc serves the {@code partner-api} group here (permitAll in SecurityConfig). */
    private static final String PARTNER_API_DOCS = "/partner/v3/api-docs/partner-api";

    /** System property that flips this test from guard mode to regenerate mode. */
    private static final String WRITE_FLAG = "cia.openapi.snapshot.write";

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "post", "put", "patch", "delete", "head", "options");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void partnerApiSnapshotsMatchLiveControllers() throws Exception {
        String body = mockMvc.perform(get(PARTNER_API_DOCS))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode live = objectMapper.readTree(body);

        if (Boolean.getBoolean(WRITE_FLAG)) {
            String pretty = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(live);
            for (Path specFile : committedSpecFiles()) {
                Files.writeString(specFile, pretty);
                System.out.println("[partner-api-snapshot] regenerated " + specFile
                        + " (" + operations(live).size() + " operations).");
            }
            System.out.println("[partner-api-snapshot] normalise with `python3 -m json.tool --indent 2 <f> <out>` "
                    + "then `mvn -pl cia-partner-api package` to regenerate postman.json");
            return;
        }

        Set<String> liveOps = operations(live);
        String regenHint = " — regenerate with `mvn -pl cia-api failsafe:integration-test "
                + "-Dit.test=PartnerApiSnapshotIT -D" + WRITE_FLAG + "=true`, then "
                + "`python3 -m json.tool --indent 2` each file and "
                + "`mvn -pl cia-partner-api package` to refresh postman.json.";

        for (Path specFile : committedSpecFiles()) {
            assertThat(Files.exists(specFile))
                    .as("committed partner snapshot not found at %s", specFile).isTrue();
            Set<String> committedOps = operations(objectMapper.readTree(Files.readString(specFile)));

            Set<String> inCodeNotInDoc = new TreeSet<>(liveOps);
            inCodeNotInDoc.removeAll(committedOps);
            Set<String> inDocNotInCode = new TreeSet<>(committedOps);
            inDocNotInCode.removeAll(liveOps);

            assertThat(inCodeNotInDoc)
                    .as("partner endpoints exist in code but are MISSING from %s%s", specFile, regenHint)
                    .isEmpty();
            assertThat(inDocNotInCode)
                    .as("partner endpoints in %s no longer exist in code%s", specFile, regenHint)
                    .isEmpty();
        }
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

    /** The two committed Partner spec files, resolved from the cia-api module dir (user.dir). */
    private List<Path> committedSpecFiles() {
        Path moduleDir = Paths.get(System.getProperty("user.dir"));
        return List.of(
                moduleDir.resolve("../../docs-site/static/openapi.json").normalize(),
                moduleDir.resolve("../cia-partner-api/docs/openapi.json").normalize());
    }
}
