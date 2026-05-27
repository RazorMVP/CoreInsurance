package com.nubeero.cia.documents.notification;

import com.github.mustachejava.Code;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheException;
import com.github.mustachejava.MustacheFactory;
import com.github.mustachejava.codes.IterableCode;
import com.nubeero.cia.common.exception.BusinessRuleException;
import org.springframework.stereotype.Component;

import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Logic-less Mustache template renderer for the F7-δ + R7 notification framework.
 * Channel- and domain-agnostic — compiles + executes Mustache against a filtered field map.
 */
@Component
public class MustacheTemplateRenderer {

    // Standard factory used for render() — partials are resolved from the classpath,
    // which is fine for rendering (partials are forbidden by policy, not by factory config).
    private final MustacheFactory factory = new DefaultMustacheFactory();

    // Strict factory used for extractVariableNames() — throws BusinessRuleException
    // immediately when the parser encounters any partial reference ({{>name}}),
    // before the AST is even built. DefaultMustacheFactory tries to load the partial
    // file during compile(), so we intercept getReader() to fail fast with our own error.
    private final MustacheFactory strictFactory = new DefaultMustacheFactory() {
        @Override
        public Reader getReader(String resourceName) {
            throw new MustacheException(
                    "UNKNOWN_TEMPLATE_VARIABLE: Mustache partials ({{>name}}) are not allowed in templates: partial reference '"
                            + resourceName + "'");
        }
    };

    /**
     * Render a Mustache template against the supplied merge fields.
     * HTML-escapes {{var}} by default; {{{var}}} passes through unescaped.
     */
    public String render(String template, Map<String, Object> fields) {
        Mustache compiled = factory.compile(new StringReader(template), "inline");
        StringWriter writer = new StringWriter();
        compiled.execute(writer, fields);
        return writer.toString();
    }

    /**
     * Return a copy of the input map containing only keys that appear in the allowlist.
     * Defence-in-depth: even if a caller passes extra merge fields, they cannot leak
     * into the rendered template.
     */
    public Map<String, Object> filterByAllowlist(Map<String, Object> fields, Set<String> allowlist) {
        Map<String, Object> filtered = new HashMap<>();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (allowlist.contains(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    /**
     * Parse the template and return the set of variable names it references.
     * Walks the AST extracting variable and section node names.
     * Throws BusinessRuleException with code UNKNOWN_TEMPLATE_VARIABLE if the
     * template references a partial ({{&gt;name}}) — partials are not allowed.
     */
    public Set<String> extractVariableNames(String template) {
        // strictFactory.getReader() fires the moment the parser hits {{>name}},
        // before the AST is assembled. Wrap its MustacheException as BusinessRuleException.
        Mustache compiled;
        try {
            compiled = strictFactory.compile(new StringReader(template), "inline");
        } catch (com.github.mustachejava.MustacheException ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "partial reference not allowed";
            throw new BusinessRuleException("UNKNOWN_TEMPLATE_VARIABLE",
                    msg.contains("UNKNOWN_TEMPLATE_VARIABLE") ? msg
                            : "UNKNOWN_TEMPLATE_VARIABLE: Mustache partial rejected — " + msg);
        }
        Set<String> names = new HashSet<>();
        walkCodes(compiled.getCodes(), names);
        return names;
    }

    private void walkCodes(Code[] codes, Set<String> names) {
        if (codes == null) return;
        for (Code code : codes) {
            // getName() is public on the Code interface — no downcast required.
            // Collect the name; for IterableCode (sections) also recurse into body codes.
            String name = code.getName();
            if (name != null && !name.isEmpty()) {
                names.add(name);
            }
            if (code instanceof IterableCode) {
                // IterableCode covers {{#section}}…{{/section}} and {{^inverted}}…{{/inverted}}
                // (NotIterableCode extends IterableCode). Recurse to pick up nested variables.
                walkCodes(code.getCodes(), names);
            }
        }
    }
}
