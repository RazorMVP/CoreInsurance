---
id: integrations
title: Integrations
sidebar_label: Integrations
---

# Integrations

Every external integration in CIA follows the same pattern: **interface → stub implementation (dev/test) → live implementation (prod)**, swapped via Spring `@Profile` — zero business logic changes required. This decouples NAICOM credential availability, KYC provider selection, and email/SMS vendor choice from feature shipping.

## The Pattern

```java
public interface NaicomIntegrationService {
    NaicomUploadResult uploadPolicy(UUID policyId);
    NaicomUploadResult uploadEndorsement(UUID endorsementId);
}

@Profile({"dev","test"})
@Service
class StubNaicomService implements NaicomIntegrationService {
    public NaicomUploadResult uploadPolicy(UUID id) {
        return NaicomUploadResult.success("NAICOM-STUB-" + id);  // deterministic mock UID
    }
}

@Profile("prod")
@Service
class NaicomRestService implements NaicomIntegrationService {
    // ... real REST client against NAICOM API
}
```

Business modules and Temporal workflows depend on the **interface**, never on a concrete adapter. Swapping requires only flipping `spring.profiles.active`.

## Catalogue

| Integration | Interface | Stub (dev/test) | Live (prod) | Trigger |
| --- | --- | --- | --- | --- |
| **NAICOM** policy/endorsement upload | `NaicomIntegrationService` | `StubNaicomService` | `NaicomRestService` | Post-approval child workflow (`NaicomUploadWorkflow`) |
| **NIID** policy upload (motor + marine) | `NiidIntegrationService` | `StubNiidService` | `NiidRestService` | Post-approval child workflow (`NiidUploadWorkflow`) |
| **KYC** individual + corporate verification | `KycVerificationService` | `MockKycService` | `DojahKycService` / `PremblyKycService` / `NibssKycService` | Sync at customer onboarding; re-triggered on KYC field update with required reason |
| **Email** transactional | `EmailNotificationService` | `LoggingEmailService` | `SendGridEmailService` / `SmtpEmailService` | Spring `ApplicationEvent` (approval, policy delivery, renewal, CFO period-reopen) |
| **SMS** transactional | `SmsNotificationService` | `LoggingSmsService` | `TermiiSmsService` / `TwilioSmsService` | Same as email |
| **Object storage** | `DocumentStorageService` | `LocalDocumentStorageService` | `MinioStorageService` / `S3StorageService` / `GCSStorageService` / `AzureBlobStorageService` | Every document upload / download (PDFs, KYC docs, NAICOM submission artifacts) |
| **AI** (optional) | `AiAssistService` | disabled (no-op bean) | `ClaudeAiAssistService` | On-demand calls from underwriting / claims triage; gated by per-tenant feature flag |

## Choosing Provider Implementations

For multi-implementation interfaces (KYC, email, SMS, storage), the active bean is selected by a property — not just by profile — so production can choose between providers without rebuilds:

```yaml
# application.yml
cia:
  kyc:
    provider: dojah     # dojah | prembly | nibss | mock
  notifications:
    email-provider: sendgrid   # sendgrid | smtp | log
    sms-provider: termii        # termii | twilio | log
  storage:
    type: minio                # minio | s3 | gcs | azure | local
```

Each provider bean carries `@ConditionalOnProperty(name="cia.kyc.provider", havingValue="dojah")` so the IoC container instantiates exactly one bean for the interface.

## Failure Behaviour

The pattern is identical for every integration:

1. **Activity layer**: Each integration call is a Temporal activity with its own retry policy. Transient failures (network, 5xx, rate-limit) are retried with exponential backoff inside the activity's `RetryOptions`.
2. **Workflow layer**: The workflow holds long-running state. NAICOM/NIID retry indefinitely (`5min → 15min → 1hr → ...`) because the regulator must eventually accept the upload. Webhook delivery retries 3 times (`30s → 2min → 10min`) then marks the registration degraded.
3. **Business outcome**: Failure of an external integration **does not block the business action**. Policy approval issues a certificate with `naicom_uid = "PENDING"` immediately; the real UID is patched in when NAICOM accepts. Customer onboarding marks `kyc_status = FAILED` if KYC rejects; the user can re-submit via KYC Update.

## NDPR Compliance Notes

- **KYC documents** uploaded during onboarding are encrypted at rest in storage (provider-side encryption + per-tenant key isolation where supported).
- **PII fields** (`id_number`, `id_document_url`, `address` on customers and directors) are encrypted in PostgreSQL via `pgcrypto` (V24 migration) using `current_setting('app.pii_key')` — set per Hikari connection from `cia.security.pii-key`. Loss of `PII_ENCRYPTION_KEY` = unrecoverable customer PII.
- **External calls** (KYC, email, SMS) log the *fact* of the call to the audit log but never the response body, to avoid logging PII outside the encryption boundary.

## When To Add a New Integration

1. Define the interface in the closest infrastructure module (`cia-integrations` for regulators / KYC, `cia-notifications` for email/SMS, `cia-storage` for object storage). The interface lives with the abstraction, never with a concrete provider.
2. Ship the stub first. Every integration MUST have a `@Profile({"dev","test"})` stub before any feature that depends on it lands — otherwise tests become flaky and local dev breaks without a live credential.
3. Add the live implementation behind a profile or property guard. Keep the constructor injection identical to the stub.
4. Add an integration test that exercises the stub path. Add a separate contract test against the live API surface (where available) under `@Tag("live")` — excluded from the default `mvn verify`.

## Webhook Dispatch (Outbound)

Webhooks are a special category — they're *outbound* HTTP calls to partner-controlled URLs, dispatched via `WebhookDispatchWorkflow`:

- Triggered by Spring `ApplicationEvent` (e.g. `PolicyApprovedEvent`, `ClaimSettledEvent`).
- Payload signed with HMAC-SHA256 using the per-partner secret (`X-CIA-Signature: sha256=<hex>`).
- `X-CIA-Timestamp` header — partners must reject payloads older than 5 minutes to prevent replay attacks.
- Retry 3× with exponential backoff (`30s / 2min / 10min`); mark partner as `DEGRADED` after 3 consecutive failures and notify the tenant System Admin.

See [Partner Webhooks](../partner/webhooks.md) for the partner-side specification.
