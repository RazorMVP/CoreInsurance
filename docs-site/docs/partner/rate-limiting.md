---
id: rate-limiting
title: Rate Limiting
sidebar_label: Rate Limiting
---

# Rate Limiting

Requests are rate-limited per `client_id` using a token bucket algorithm.

## Tiers

| Tier | Requests/minute | Burst | Configured by |
|------|----------------|-------|---------------|
| Default | 60 | 100 | System default |
| Standard | 300 | 500 | Insurance company admin |
| Premium | 1,000 | 2,000 | Insurance company admin |

Contact the insurance company's admin to upgrade your tier.

## Response Headers

Every response includes your current limit state:

```
X-RateLimit-Limit: 300
X-RateLimit-Remaining: 247
X-RateLimit-Reset: 1745155440
```

`X-RateLimit-Reset` is a Unix timestamp indicating when your bucket refills.

## HTTP 429

When your limit is exceeded:

```
HTTP/1.1 429 Too Many Requests
Retry-After: 13
X-RateLimit-Limit: 300
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1745155440
```

Implement exponential backoff with jitter when you receive a 429:

```typescript
async function fetchWithRetry(url: string, token: string, maxRetries = 3) {
  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    const response = await fetch(url, {
      headers: { Authorization: `Bearer ${token}` },
    });

    if (response.status !== 429) return response;

    const retryAfter = parseInt(response.headers.get('Retry-After') ?? '1', 10);
    const jitter = Math.random() * 1000;
    await new Promise(r => setTimeout(r, retryAfter * 1000 + jitter));
  }
  throw new Error('Rate limit exceeded after retries');
}
```
