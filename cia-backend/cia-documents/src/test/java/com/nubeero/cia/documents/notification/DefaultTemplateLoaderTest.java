package com.nubeero.cia.documents.notification;

import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultTemplateLoaderTest {

    private final DefaultTemplateLoader loader = new DefaultTemplateLoader();

    @Test
    void loadsReceiptEmailSubject() {
        String subject = loader.subjectFor(NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL);
        assertThat(subject).contains("{{receiptNumber}}");
    }

    @Test
    void loadsReceiptEmailBody() {
        String body = loader.bodyFor(NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL);
        assertThat(body).contains("{{customerName}}").contains("<html");
    }

    @Test
    void loadsPaymentVoucherEmailSubject() {
        String subject = loader.subjectFor(NotificationTemplateType.PAYMENT_VOUCHER, NotificationChannel.EMAIL);
        assertThat(subject).contains("{{paymentNumber}}");
    }

    @Test
    void loadsReceiptSmsBody() {
        String body = loader.bodyFor(NotificationTemplateType.RECEIPT, NotificationChannel.SMS);
        assertThat(body).contains("{{customerName}}").contains("{{receiptNumber}}");
    }

    @Test
    void smsHasNoSubject() {
        String subject = loader.subjectFor(NotificationTemplateType.RECEIPT, NotificationChannel.SMS);
        assertThat(subject).isNull();
    }

    @Test
    void missingFileThrows() {
        DefaultTemplateLoader brokenLoader = new DefaultTemplateLoader() {
            @Override
            protected String classpathPath(NotificationTemplateType t, NotificationChannel c, String ext) {
                return "/nonexistent/template.txt";
            }
        };
        assertThatThrownBy(() -> brokenLoader.bodyFor(
                NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nonexistent");
    }
}
