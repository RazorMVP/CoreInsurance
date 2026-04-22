---
id: sandbox
title: Sandbox Environment
sidebar_label: Sandbox
---

# Sandbox Environment

The sandbox lets you test your integration without creating real policies, triggering real NAICOM uploads, or sending real notifications.

## Sandbox Base URL

```
https://api.cia.app/partner/v1/sandbox/
```

## Sandbox Credentials

Your sandbox `client_id` and `client_secret` are separate from production credentials. Request them from the insurance company admin alongside your production credentials.

Sandbox tokens are issued from the same Keycloak realm but scoped to `sandbox` only:

```
grant_type=client_credentials
&client_id=YOUR_SANDBOX_CLIENT_ID
&client_secret=YOUR_SANDBOX_CLIENT_SECRET
```

## Sandbox Behaviour

| Feature | Sandbox | Production |
|---------|---------|-----------|
| Policy creation | ✅ Creates record | ✅ Creates record |
| NAICOM upload | Stub (instant mock UID) | Async, real NAICOM API |
| NIID registration | Stub | Async, real NIID API |
| KYC verification | Mock (always passes) | Real provider |
| Email/SMS notifications | Logged only (not sent) | Sent to real recipients |
| Financial records | Created in sandbox schema | Created in production schema |
| Webhooks | Delivered to registered URLs | Delivered to registered URLs |

All sandbox responses include `"sandbox": true`:

```json
{
  "data": {
    "policyId": "3fa85f64-...",
    "policyNumber": "CIA-SBX/2026/000001",
    "status": "ACTIVE",
    "sandbox": true
  }
}
```

## Resetting Sandbox Data

The insurance company admin can reset all sandbox data from the Setup module → Partner Management → Sandbox.
