package com.nubeero.cia.api.tenant;

import com.nubeero.cia.setup.keycloak.BootstrapRoles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapRolesDriftTest {

    /**
     * Outer pattern: captures the full argument list inside has[Any](Role|Authority)(...).
     * Group 1 = the raw argument string (everything between the outermost parens).
     */
    private static final Pattern HAS_AUTH_CALL =
            Pattern.compile("has(?:Any)?(?:Role|Authority)\\(([^)]+)\\)");

    /** Inner pattern: extracts each quoted token from the argument list. */
    private static final Pattern QUOTED_TOKEN =
            Pattern.compile("'([A-Za-z0-9_:]+)'");

    @Test
    void everyReferencedAuthorityIsCoveredByBootstrapRoles() throws IOException {
        Path backend = Paths.get(System.getProperty("user.dir")).getParent(); // cia-api -> cia-backend
        Set<String> covered = new HashSet<>();
        for (String role : BootstrapRoles.ALL) {
            covered.add(normalise(role));
        }

        Set<String> referenced = new TreeSet<>();
        try (Stream<Path> files = Files.walk(backend)) {
            files.filter(p -> p.toString().endsWith(".java"))
                 .filter(p -> p.toString().contains("/src/main/"))
                 .forEach(p -> scan(p, referenced));
        }

        assertThat(referenced)
            .as("file walk found no authorities — check user.dir path resolution")
            .hasSizeGreaterThan(20);

        Set<String> missing = new TreeSet<>();
        for (String auth : referenced) {
            if (!covered.contains(normalise(auth))) missing.add(auth);
        }

        assertThat(missing)
            .as("authorities referenced in controllers but missing from BootstrapRoles.ALL — "
                + "add them (and create the Keycloak role) so the bootstrap admin keeps full access")
            .isEmpty();
    }

    private static void scan(Path file, Set<String> out) {
        try {
            String src = Files.readString(file);
            Matcher callMatcher = HAS_AUTH_CALL.matcher(src);
            while (callMatcher.find()) {
                String argGroup = callMatcher.group(1);
                Matcher tokenMatcher = QUOTED_TOKEN.matcher(argGroup);
                while (tokenMatcher.find()) {
                    out.add(tokenMatcher.group(1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String normalise(String s) {
        return s.replace(':', '_').toUpperCase(Locale.ROOT);
    }
}
