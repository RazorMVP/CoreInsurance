---
id: integrations
title: Integrations
sidebar_label: Integrations
---

# Integrations

## Current Position

KYC, NAICOM, and NIID live integrations are intentionally deferred until the
system is being prepared for live deployment with confirmed provider contracts,
credentials, endpoint specifications, and test accounts.

Until that go-live work is complete, the application must fail closed:

| Area | Local/dev behavior | Production-like behavior |
| --- | --- | --- |
| KYC | `KYC_PROVIDER=mock` is allowed only in `dev` and `test`. | `mock` is rejected; `dojah` and `prembly` currently fail startup until implemented. |
| NAICOM | `NAICOM_MODE=stub` is allowed only in `dev` and `test`. | `stub` is rejected; `live` currently fails startup until implemented. |
| NIID | `NIID_MODE=stub` is allowed only in `dev` and `test`. | `stub` is rejected; `live` currently fails startup until implemented. |

This prevents a deployment from appearing production-ready while regulatory or
KYC calls are still backed by placeholder adapters.

## Go-Live Entry Criteria

Before enabling any live provider:

| Requirement | Outcome |
| --- | --- |
| Provider contract | Signed provider contract, base URL, authentication method, request/response schema, rate limits, and support escalation path are recorded. |
| Secrets | API credentials are stored only in the target secret manager; no provider secrets are checked into Git or exposed through frontend environment variables. |
| Timeouts and retries | HTTP clients use bounded connect/read timeouts, bounded retries, and explicit failure states. |
| Redaction | Logs, audit records, and workflow payload history exclude ID numbers, RC numbers, policy payloads, vehicle identifiers, and provider secrets. |
| Contract tests | Sandbox/provider contract tests pass before the startup block is removed. |

## Startup Guardrail

Production-like profiles reject dev/test defaults through
`ProductionSafetyValidator`. Pending live adapter beans also fail startup until
their real implementations replace the current blockers.
