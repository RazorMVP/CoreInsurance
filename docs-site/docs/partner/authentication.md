---
id: authentication
title: Authentication
sidebar_label: Authentication
---

# Authentication

The Partner API uses **OAuth 2.0 Client Credentials** grant. Your application authenticates machine-to-machine — no human login flow, no browser redirect.

## Step 1: Get Your Credentials

A CIA System Admin creates a Partner App in the Setup module. You receive:

- `client_id` — your application identifier
- `client_secret` — your application secret (shown once; store securely)
- `tenant_realm` — the Keycloak realm slug for this insurance company

## Step 2: Request an Access Token

```
POST https://auth.cia.app/realms/{tenant_realm}/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
&client_id=YOUR_CLIENT_ID
&client_secret=YOUR_CLIENT_SECRET
```

**Response:**

```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expires_in": 300,
  "token_type": "Bearer",
  "scope": "products:read quotes:create policies:create"
}
```

Tokens expire in 5 minutes. Request a new token before each batch of API calls, or implement token caching with a 30-second buffer before expiry.

## Step 3: Attach the Token

Send the token in the `Authorization` header on every API request:

```
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

---

## Code Examples

### JavaScript / TypeScript

```typescript
async function getAccessToken(): Promise<string> {
  const params = new URLSearchParams({
    grant_type: 'client_credentials',
    client_id: process.env.CIA_CLIENT_ID!,
    client_secret: process.env.CIA_CLIENT_SECRET!,
  });

  const response = await fetch(
    `https://auth.cia.app/realms/${process.env.CIA_TENANT_REALM}/protocol/openid-connect/token`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: params,
    }
  );

  const data = await response.json();
  return data.access_token;
}

async function callApi(path: string) {
  const token = await getAccessToken();
  return fetch(`https://api.cia.app/partner/v1${path}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
}
```

### Python

```python
import os
import requests

def get_access_token() -> str:
    response = requests.post(
        f"https://auth.cia.app/realms/{os.environ['CIA_TENANT_REALM']}"
        "/protocol/openid-connect/token",
        data={
            "grant_type": "client_credentials",
            "client_id": os.environ["CIA_CLIENT_ID"],
            "client_secret": os.environ["CIA_CLIENT_SECRET"],
        },
    )
    response.raise_for_status()
    return response.json()["access_token"]

def call_api(path: str) -> dict:
    token = get_access_token()
    response = requests.get(
        f"https://api.cia.app/partner/v1{path}",
        headers={"Authorization": f"Bearer {token}"},
    )
    response.raise_for_status()
    return response.json()
```

### Java

```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class CiaApiClient {

    private final HttpClient http = HttpClient.newHttpClient();

    public String getAccessToken() throws Exception {
        String body = "grant_type=client_credentials"
            + "&client_id=" + URLEncoder.encode(System.getenv("CIA_CLIENT_ID"), StandardCharsets.UTF_8)
            + "&client_secret=" + URLEncoder.encode(System.getenv("CIA_CLIENT_SECRET"), StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://auth.cia.app/realms/" + System.getenv("CIA_TENANT_REALM")
                + "/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        // Parse JSON to extract access_token
        return parseAccessToken(response.body());
    }
}
```

### cURL

```bash
TOKEN=$(curl -s \
  -d "grant_type=client_credentials" \
  -d "client_id=$CIA_CLIENT_ID" \
  -d "client_secret=$CIA_CLIENT_SECRET" \
  "https://auth.cia.app/realms/$CIA_TENANT_REALM/protocol/openid-connect/token" \
  | jq -r '.access_token')

curl -H "Authorization: Bearer $TOKEN" \
  https://api.cia.app/partner/v1/products
```

---

## OAuth2 Scopes

Each API endpoint requires a specific scope. Your `client_id` is granted scopes by the insurance company System Admin.

| Scope | Grants Access To |
|-------|----------------|
| `products:read` | List products and classes of business |
| `quotes:create` | Create quotes |
| `quotes:read` | Retrieve quotes |
| `customers:create` | Register customers (triggers KYC) |
| `customers:read` | Retrieve customer details and KYC status |
| `policies:create` | Bind policies from approved quotes |
| `policies:read` | Retrieve policy details and certificates |
| `claims:create` | Submit claim notifications |
| `claims:read` | Retrieve claim status and details |
| `webhooks:manage` | Register, list, and delete webhook endpoints |

---

## Token Errors

| HTTP Status | Error | Cause |
|------------|-------|-------|
| `401 Unauthorized` | `invalid_client` | Wrong `client_id` or `client_secret` |
| `401 Unauthorized` | `Token expired` | Access token has expired — request a new one |
| `403 Forbidden` | `insufficient_scope` | Your app lacks the required scope for this endpoint |
| `429 Too Many Requests` | — | Rate limit exceeded — see `Retry-After` header |
