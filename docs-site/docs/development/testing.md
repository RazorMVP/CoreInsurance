---
id: testing
title: Testing
sidebar_label: Testing
---

## Testing Strategy

CIAGB uses four testing layers. Every layer must pass in CI before a PR is merged.

| Layer | Tool | Scope | Runs in CI |
| --- | --- | --- | --- |
| Backend unit | JUnit 5 + Mockito | Service logic, calculations, DTOs | Yes |
| Backend integration | JUnit 5 + Testcontainers | Repositories, multi-tenant routing | Yes |
| Frontend unit/component | Vitest + Testing Library | Utility functions, hooks, critical components | Yes |
| E2E | Playwright | Golden paths per module | Yes (stubbed, enable per module) |

**Minimum coverage:** 80% line coverage on backend business logic (`cia-service` classes).

---

## Backend Tests

### Unit Tests

Unit tests live alongside the class under test in `src/test/java/`:

```text
cia-policy/src/test/java/com/nubeero/cia/policy/
├── PolicyServiceTest.java
├── PremiumCalculatorTest.java
└── dto/PolicyResponseTest.java
```

Use Mockito to stub repository and service dependencies:

```java
@ExtendWith(MockitoExtension.class)
class PolicyServiceTest {

    @Mock PolicyRepository policyRepo;
    @Mock WorkflowClient workflowClient;
    @InjectMocks PolicyService policyService;

    @Test
    void submitForApproval_setsStatusToPendingApproval() {
        // arrange / act / assert
    }
}
```

### Integration Tests (Testcontainers)

Integration tests use a real PostgreSQL container. The base class handles container lifecycle:

```java
@SpringBootTest
@Testcontainers
class PolicyRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16").withDatabaseName("cia_test");

    @DynamicPropertySource
    static void postgresProps(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", postgres::getJdbcUrl);
        reg.add("spring.datasource.username", postgres::getUsername);
        reg.add("spring.datasource.password", postgres::getPassword);
    }
}
```

**Run integration tests only:**

```bash
cd cia-backend
./mvnw verify -pl cia-policy -Dtest='*IT'
```

### Running the Full Suite

```bash
cd cia-backend
./mvnw verify          # compiles, runs all tests (unit + integration)
./mvnw test            # unit tests only (skips Testcontainers)
```

---

## Frontend Tests

Tests live alongside the component or hook:

```text
cia-frontend/src/modules/policy/
├── PolicyList.tsx
├── PolicyList.test.tsx       # component test
└── usePolicies.test.ts       # hook test
```

**Run all frontend tests:**

```bash
cd cia-frontend
npm test                # watch mode
npm run test:run        # single pass (CI)
npm run test:coverage   # with coverage report
```

### Component Testing Pattern

```tsx
import { render, screen } from '@testing-library/react';
import { QueryClientProvider, QueryClient } from '@tanstack/react-query';
import PolicyList from './PolicyList';

test('renders policy number in the list', async () => {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <PolicyList />
    </QueryClientProvider>
  );
  expect(await screen.findByText(/POL-2024-/)).toBeInTheDocument();
});
```

---

## E2E Tests (Playwright)

Playwright tests cover the golden path for each module. They run against a fully booted local stack (docker-compose + cia-api + cia-frontend).

Tests live in `cia-frontend/e2e/`:

```text
cia-frontend/e2e/
├── policy/
│   ├── create-policy.spec.ts
│   └── approval-flow.spec.ts
├── claims/
│   └── submit-claim.spec.ts
└── auth/
    └── login.spec.ts
```

**Run E2E tests:**

```bash
cd cia-frontend
npx playwright test                  # all tests (headless)
npx playwright test --ui             # interactive Playwright UI
npx playwright test e2e/policy/      # single module
```

> Before running E2E tests ensure the local stack is fully up: `docker-compose up -d` and `mvn spring-boot:run -pl cia-api`.

---

## What to Test

### Always test

- Every business rule in a service method (premium calculation, approval routing, status transitions).
- Every repository method with a custom `@Query`.
- Every REST controller endpoint (status codes, response shape, `@PreAuthorize` enforcement).

### Don't test

- Framework plumbing (Spring beans wiring, JPA entity field getters).
- Stub implementations (`LoggingEmailService`, `StubNaicomService`) — these are tested implicitly via integration tests.
- Trivial DTOs with no logic.
