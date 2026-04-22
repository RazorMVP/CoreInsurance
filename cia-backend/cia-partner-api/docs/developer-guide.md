# CIA Partner Open API — Developer Guide

## Overview

The CIA Partner API allows Insurtech companies to connect to an insurance company's products and services programmatically. It uses OAuth2 Client Credentials for machine-to-machine authentication.

**Base URL:** `/partner/v1/`  
**OpenAPI Spec:** `/partner/v3/api-docs`  
**Swagger UI:** `/partner/docs`

---

## Authentication

### 1. Obtain credentials

A System Admin creates your partner app via **Setup → Partner Management**. You receive:
- `client_id`
- `client_secret`
- List of granted scopes

### 2. Get an access token

```http
POST {keycloakUrl}/realms/{tenantRealm}/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
&client_id=your-client-id
&client_secret=your-client-secret
```

The response contains `access_token`. Tokens expire in 5 minutes by default.

### 3. Attach the token

```http
Authorization: Bearer {access_token}
```

---

## Scopes

Each endpoint requires a specific scope. Request only the scopes your integration needs.

| Scope | Endpoints |
|---|---|
| `products:read` | List/get products and classes |
| `quotes:create` | Create quotes |
| `quotes:read` | Retrieve quotes |
| `customers:create` | Register customers |
| `customers:read` | Get customer details |
| `policies:create` | Bind policies |
| `policies:read` | Get policies and documents |
| `claims:create` | Submit claims |
| `claims:read` | Get claim status |
| `webhooks:manage` | Register and manage webhooks |

---

## Quick Start

### Step 1 — Get a quote

```http
POST /partner/v1/quotes
Authorization: Bearer {token}

{
  "customerId": "...",
  "productId": "...",
  "classOfBusinessId": "...",
  "risks": [
    { "description": "Motor vehicle", "sumInsured": 5000000 }
  ]
}
```

### Step 2 — Bind a policy

Once the quote is approved internally:

```http
POST /partner/v1/policies?quoteId={quoteId}
Authorization: Bearer {token}
```

### Step 3 — Submit a claim

```http
POST /partner/v1/policies/{policyId}/claims
Authorization: Bearer {token}

{
  "policyId": "...",
  "incidentDate": "2026-04-01",
  "reportedDate": "2026-04-03",
  "description": "Collision on Lagos-Ibadan expressway",
  "estimatedLoss": 800000
}
```

---

## Webhooks

### Registering a webhook

```http
POST /partner/v1/webhooks
Authorization: Bearer {token}

{
  "targetUrl": "https://your-app.com/webhooks/cia",
  "secret": "your-signing-secret-min-16-chars",
  "eventTypes": ["policy.bound", "claim.approved", "claim.settled"]
}
```

### Verifying webhook signatures

All webhook deliveries include:
- `X-CIA-Signature: sha256=<HMAC-SHA256(secret, rawBody)>`
- `X-CIA-Timestamp: <unix epoch seconds>`

Verify the signature and reject payloads older than 5 minutes to prevent replay attacks.

```python
import hmac, hashlib, time

def verify(secret, body, signature, timestamp):
    if abs(time.time() - int(timestamp)) > 300:
        return False  # replay attack
    expected = "sha256=" + hmac.new(
        secret.encode(), body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, signature)
```

### Webhook events

| Event | Trigger |
|---|---|
| `policy.bound` | Policy successfully issued |
| `policy.endorsed` | Endorsement applied |
| `claim.approved` | Claim approved, DV generated |
| `claim.settled` | Payment executed |
| `quote.created` | Quote generated |

---

## Rate Limits

| Plan | Requests/minute |
|---|---|
| Starter | 60 |
| Growth | 300 |
| Enterprise | 1,000 |

Rate limit headers are returned on every response:
```
X-RateLimit-Limit: 300
X-RateLimit-Remaining: 247
X-RateLimit-Reset: 1745155440
```

HTTP 429 is returned when the limit is exceeded. Retry after the `Retry-After` header value (seconds).

---

## Error Format

All errors follow the standard envelope:

```json
{
  "errors": [
    {
      "code": "RESOURCE_NOT_FOUND",
      "message": "Policy with id ... not found"
    }
  ]
}
```

Common error codes:
- `INSUFFICIENT_SCOPE` — JWT lacks required scope
- `RESOURCE_NOT_FOUND` — Entity not found
- `BUSINESS_RULE_VIOLATION` — Operation not allowed in current state
- `VALIDATION_ERROR` — Request body failed validation

---

## Sandbox

Set `baseUrl` to the sandbox endpoint to test without creating real records:

```
/partner/v1/sandbox/
```

All sandbox responses include `"sandbox": true`. NAICOM/NIID/KYC calls use stub adapters regardless of production config.
