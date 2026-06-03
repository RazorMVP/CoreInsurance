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

    private static final Pattern AUTHORITY =
            Pattern.compile("has(?:Role|Authority)\\(\\s*'([A-Za-z0-9_:]+)'");

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
            Matcher m = AUTHORITY.matcher(src);
            while (m.find()) out.add(m.group(1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String normalise(String s) {
        return s.replace(':', '_').toUpperCase(Locale.ROOT);
    }
}
