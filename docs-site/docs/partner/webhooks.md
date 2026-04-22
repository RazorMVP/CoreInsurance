---
id: webhooks
title: Webhooks
sidebar_label: Webhooks
---

# Webhooks

Register a URL to receive real-time event notifications instead of polling the API.

## Registering a Webhook

```
POST /partner/v1/webhooks
Authorization: Bearer {token}
Content-Type: application/json

{
  "url": "https://your-app.com/cia-webhook",
  "eventTypes": ["policy.bound", "claim.settled"],
  "description": "Production webhook"
}
```

## Event Types

| Event | Trigger |
|-------|---------|
| `quote.created` | Quote generated via partner API |
| `quote.expired` | Quote passed validity window |
| `policy.bound` | Policy successfully issued |
| `policy.endorsed` | Endorsement applied |
| `policy.cancelled` | Policy cancelled |
| `claim.registered` | Claim notification received |
| `claim.approved` | Claim approved and DV generated |
| `claim.settled` | Payment executed |
| `kyc.completed` | KYC verification result returned |
| `renewal.due` | Policy approaching renewal date |

## Payload Envelope

```json
{
  "id": "evt_01HX9MABCDE",
  "event": "policy.bound",
  "timestamp": "2026-04-22T14:23:00Z",
  "tenant_id": "tenant_acme",
  "data": {
    "policyId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "policyNumber": "CIA/2026/000123",
    "status": "ACTIVE"
  }
}
```

## Verifying Signatures

Every webhook request includes a signature and timestamp:

```
X-CIA-Signature: sha256=a1b2c3d4...
X-CIA-Timestamp: 1745155380
```

Verify the signature server-side:

```typescript
import crypto from 'crypto';

function verifyWebhook(
  rawBody: string,
  signature: string,
  timestamp: string,
  secret: string
): boolean {
  // Reject payloads older than 5 minutes (replay attack prevention)
  const age = Math.floor(Date.now() / 1000) - parseInt(timestamp, 10);
  if (age > 300) return false;

  const expected = 'sha256=' + crypto
    .createHmac('sha256', secret)
    .update(rawBody)
    .digest('hex');

  return crypto.timingSafeEqual(
    Buffer.from(signature),
    Buffer.from(expected)
  );
}
```

```python
import hmac
import hashlib
import time

def verify_webhook(raw_body: bytes, signature: str, timestamp: str, secret: str) -> bool:
    # Reject payloads older than 5 minutes
    if time.time() - int(timestamp) > 300:
        return False

    expected = "sha256=" + hmac.new(
        secret.encode(), raw_body, hashlib.sha256
    ).hexdigest()

    return hmac.compare_digest(signature, expected)
```

## Retry Policy

Failed deliveries (non-2xx response or timeout) are retried 3 times with exponential backoff:

| Attempt | Delay |
|---------|-------|
| 1st retry | 30 seconds |
| 2nd retry | 2 minutes |
| 3rd retry | 10 minutes |

After 3 consecutive failures, the webhook is marked **degraded** and the insurance company admin is notified. Your endpoint must respond within 5 seconds.

## Idempotency

Each event has a unique `id`. If you receive the same `id` twice (rare, during retries), process it only once. Store processed event IDs in a set with a 24-hour TTL.
