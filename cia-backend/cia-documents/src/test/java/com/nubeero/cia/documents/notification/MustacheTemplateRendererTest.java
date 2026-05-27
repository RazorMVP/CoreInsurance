package com.nubeero.cia.documents.notification;

import com.nubeero.cia.common.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MustacheTemplateRendererTest {

    private final MustacheTemplateRenderer renderer = new MustacheTemplateRenderer();

    @Test
    void substitutesSimpleVariables() {
        String out = renderer.render(
                "Hi {{customerName}}, your receipt {{receiptNumber}} is ready.",
                Map.of("customerName", "Acme Ltd", "receiptNumber", "REC-001"));
        assertThat(out).isEqualTo("Hi Acme Ltd, your receipt REC-001 is ready.");
    }

    @Test
    void rendersConditionalSectionWhenTruthy() {
        String out = renderer.render(
                "Hello{{#vip}} VIP{{/vip}} customer",
                Map.of("vip", true));
        assertThat(out).isEqualTo("Hello VIP customer");
    }

    @Test
    void omitsConditionalSectionWhenFalsy() {
        String out = renderer.render(
                "Hello{{#vip}} VIP{{/vip}} customer",
                Map.of("vip", false));
        assertThat(out).isEqualTo("Hello customer");
    }

    @Test
    void htmlEscapesByDefault() {
        String out = renderer.render(
                "Note: {{note}}",
                Map.of("note", "<script>alert('xss')</script>"));
        assertThat(out).isEqualTo("Note: &lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;");
    }

    @Test
    void triplebracesEscapeUnescaped() {
        String out = renderer.render(
                "Raw: {{{html}}}",
                Map.of("html", "<b>bold</b>"));
        assertThat(out).isEqualTo("Raw: <b>bold</b>");
    }

    @Test
    void filterByAllowlist_keepsAllowedKeys() {
        Map<String, Object> filtered = renderer.filterByAllowlist(
                Map.of("a", 1, "b", 2, "c", 3),
                Set.of("a", "c"));
        assertThat(filtered).containsOnlyKeys("a", "c");
    }

    @Test
    void filterByAllowlist_dropsDisallowedKeys() {
        Map<String, Object> filtered = renderer.filterByAllowlist(
                Map.of("safe", "ok", "leaky", "secret"),
                Set.of("safe"));
        assertThat(filtered).doesNotContainKey("leaky");
    }

    @Test
    void extractVariableNames_returnsValueAndSectionNames() {
        Set<String> names = renderer.extractVariableNames(
                "Hi {{name}}, your {{#hasReceipt}}receipt {{number}}{{/hasReceipt}} is here.");
        assertThat(names).containsExactlyInAnyOrder("name", "hasReceipt", "number");
    }

    @Test
    void extractVariableNames_throwsOnPartialReference() {
        assertThatThrownBy(() ->
                renderer.extractVariableNames("Hi {{>some-partial}}"))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("UNKNOWN_TEMPLATE_VARIABLE")
            .hasMessageContaining("partial");
    }
}
