---
id: overview
title: Partner API Overview
sidebar_label: Overview
---

# Partner API Overview

The CIA Partner API allows Insurtech companies — aggregators, digital brokers, and embedded insurance providers — to connect to an insurance company's products and services programmatically.

## Base URL

```
https://api.cia.app/partner/v1/
```

Each insurance company runs on its own subdomain. Replace `api.cia.app` with your assigned tenant endpoint.

## API Versioning

URI-based versioning (`/partner/v1/`, `/partner/v2/`). Breaking changes always bump the version. Both versions run simultaneously during deprecation windows.

## Interactive Docs

- **Swagger UI**: `/partner/docs`
- **OpenAPI JSON**: `/partner/v3/api-docs`

## Standard Response Envelope

All responses use a consistent wrapper:

```json
{
  "data": { ... },
  "meta": {
    "page": 0,
    "size": 20,
    "total": 143
  },
  "errors": []
}
```

Error responses populate `errors` and return an appropriate HTTP status:

```json
{
  "data": null,
  "meta": null,
  "errors": [
    {
      "code": "VALIDATION_ERROR",
      "field": "sumInsured",
      "message": "Sum insured must be greater than 0"
    }
  ]
}
```

## Rate Limiting

Every response includes rate limit headers:

```
X-RateLimit-Limit: 300
X-RateLimit-Remaining: 247
X-RateLimit-Reset: 1745155440
```

When exceeded, HTTP `429` is returned with a `Retry-After` header. See [Rate Limiting](rate-limiting) for tier details.

## Sandbox

A sandbox environment is available for testing without creating real policies. See [Sandbox](sandbox).
