package com.nubeero.cia.documents.notification;

import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the JAR-default Mustache template content for a given
 * (template_type, channel) pair. The composer falls back to these when
 * the per-tenant override row is missing for the corresponding field.
 *
 * Files live under templates/notifications/{channel-lc}/{type-kebab-case}.{subject|html|txt}
 *  - notifications/email/receipt.subject          (single line)
 *  - notifications/email/receipt.html
 *  - notifications/email/payment-voucher.subject  (single line)
 *  - notifications/email/payment-voucher.html
 *  - notifications/sms/receipt.txt
 *  - notifications/sms/payment-voucher.txt
 *
 * SMS has no subject; subjectFor(..., SMS) returns null by contract.
 */
@Component
public class DefaultTemplateLoader {

    public String subjectFor(NotificationTemplateType type, NotificationChannel channel) {
        if (channel == NotificationChannel.SMS) {
            return null;
        }
        return readResource(classpathPath(type, channel, "subject")).trim();
    }

    public String bodyFor(NotificationTemplateType type, NotificationChannel channel) {
        String ext = (channel == NotificationChannel.EMAIL) ? "html" : "txt";
        return readResource(classpathPath(type, channel, ext));
    }

    protected String classpathPath(NotificationTemplateType type, NotificationChannel channel, String ext) {
        String typeKebab = type.name().toLowerCase().replace('_', '-');
        String channelLc = channel.name().toLowerCase();
        return "/templates/notifications/" + channelLc + "/" + typeKebab + "." + ext;
    }

    private String readResource(String classpathPath) {
        try (InputStream in = getClass().getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IllegalStateException("Missing template resource: " + classpathPath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read template resource: " + classpathPath, e);
        }
    }
}
