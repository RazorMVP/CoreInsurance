package com.nubeero.cia.partner.webhook;

import com.nubeero.cia.workflow.webhook.WebhookDeliveryResult;
import com.nubeero.cia.workflow.webhook.WebhookDispatchActivity;
import com.nubeero.cia.workflow.webhook.WebhookDispatchRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDispatchActivityImpl implements WebhookDispatchActivity {

    private final WebhookRegistrationRepository webhookRegistrationRepository;
    private final WebhookDeliveryLogRepository deliveryLogRepository;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public WebhookDeliveryResult send(WebhookDispatchRequest request) {
        return webhookRegistrationRepository
                .findById(UUID.fromString(request.getWebhookRegistrationId()))
                .map(registration -> dispatch(registration, request))
                .orElseGet(() -> {
                    log.warn("WebhookRegistration not found id={}", request.getWebhookRegistrationId());
                    return WebhookDeliveryResult.builder()
                            .success(false)
                            .errorMessage("Webhook registration not found")
                            .build();
                });
    }

    private WebhookDeliveryResult dispatch(WebhookRegistration registration, WebhookDispatchRequest request) {
        WebhookDeliveryResult result;
        try {
            String signature = sign(request.getPayloadJson(), registration.getSecret());
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(registration.getTargetUrl()))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("X-CIA-Event", request.getEventType())
                    .header("X-CIA-Signature", "sha256=" + signature)
                    .header("X-CIA-Timestamp", request.getTimestamp().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(request.getPayloadJson()))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            log.info("Webhook dispatched registrationId={} eventType={} status={}",
                    request.getWebhookRegistrationId(), request.getEventType(), response.statusCode());
            result = WebhookDeliveryResult.builder()
                    .success(success)
                    .httpStatus(response.statusCode())
                    .responseBody(response.body())
                    .build();
        } catch (Exception e) {
            log.error("Webhook dispatch failed registrationId={}", request.getWebhookRegistrationId(), e);
            result = WebhookDeliveryResult.builder().success(false).errorMessage(e.getMessage()).build();
        }

        deliveryLogRepository.save(WebhookDeliveryLog.builder()
                .webhookRegistrationId(registration.getId())
                .eventType(request.getEventType())
                .payloadJson(request.getPayloadJson())
                .success(result.isSuccess())
                .httpStatus(result.getHttpStatus() > 0 ? result.getHttpStatus() : null)
                .responseBody(result.getResponseBody())
                .errorMessage(result.getErrorMessage())
                .build());

        return result;
    }

    private String sign(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
