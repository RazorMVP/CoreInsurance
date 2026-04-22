---
id: adding-a-module
title: Adding a Module
sidebar_label: Adding a Module
---

## When to Add a Module

Each top-level business domain gets its own Maven module. Utility concerns shared across domains belong in one of the cross-cutting modules (`cia-common`, `cia-storage`, `cia-notifications`, `cia-workflow`, `cia-documents`, `cia-integrations`).

Do not add a module for a sub-feature of an existing domain — add it to the existing module.

---

## Step 1: Create the Maven Module

```bash
# From cia-backend/
mvn archetype:generate \
  -DgroupId=com.nubeero.cia \
  -DartifactId=cia-newmodule \
  -DarchetypeArtifactId=maven-archetype-quickstart \
  -DinteractiveMode=false
```

Edit the generated `pom.xml`:

```xml
<parent>
  <groupId>com.nubeero.cia</groupId>
  <artifactId>cia-backend</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</parent>

<artifactId>cia-newmodule</artifactId>
<packaging>jar</packaging>

<dependencies>
  <!-- Always required -->
  <dependency>
    <groupId>com.nubeero.cia</groupId>
    <artifactId>cia-common</artifactId>
  </dependency>

  <!-- Add only what this module directly uses -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
  </dependency>
</dependencies>
```

Add the module to `cia-backend/pom.xml` `<modules>` list:

```xml
<modules>
  <!-- existing modules -->
  <module>cia-newmodule</module>
</modules>
```

---

## Step 2: Add the Module to `cia-api`

`cia-api` is the assembly module — it pulls in all business modules and runs as the executable JAR.

In `cia-api/pom.xml`:

```xml
<dependency>
  <groupId>com.nubeero.cia</groupId>
  <artifactId>cia-newmodule</artifactId>
</dependency>
```

---

## Step 3: Create the Package Structure

```text
cia-newmodule/src/main/java/com/nubeero/cia/newmodule/
├── NewModuleService.java
├── NewModuleController.java
├── NewEntity.java                  # extends BaseEntity
├── NewEntityRepository.java
└── dto/
    ├── NewEntityRequest.java
    └── NewEntityResponse.java
```

### Entity

```java
@Entity
@Table(name = "new_entities")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class NewEntity extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NewEntityStatus status;
}
```

### Service

```java
@Service
@Transactional
@RequiredArgsConstructor
public class NewModuleService {

    private final NewEntityRepository repo;

    @Transactional(readOnly = true)
    public NewEntityResponse findById(UUID id) {
        return repo.findById(id)
                .map(NewEntityResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("NewEntity", id));
    }
}
```

### Controller

```java
@RestController
@RequestMapping("/api/v1/new-entities")
@RequiredArgsConstructor
public class NewModuleController {

    private final NewModuleService service;

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('newmodule:view')")
    public ApiResponse<NewEntityResponse> getById(@PathVariable UUID id) {
        return ApiResponse.ok(service.findById(id));
    }
}
```

---

## Step 4: Create the Database Migration

Create a new Flyway migration in `cia-api/src/main/resources/db/migration/`:

```sql
-- V<next_number>__add_new_module_tables.sql
CREATE TABLE new_entities (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    status      VARCHAR(50)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  VARCHAR(255),
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT chk_new_entities_status CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE'))
);

CREATE INDEX idx_new_entities_status ON new_entities (status);
```

**Rule:** Never edit an existing migration file. Always create a new versioned file.

---

## Step 5: Add Keycloak Roles

Add the new module's roles to the Keycloak dev realm export (`docker/keycloak/realm-dev.json`) and document them in [Access Control](../architecture/security).

Standard role set per module:

| Role | Authority | Used by |
| --- | --- | --- |
| `newmodule_view` | `newmodule:view` | GET endpoints |
| `newmodule_create` | `newmodule:create` | POST endpoints |
| `newmodule_update` | `newmodule:update` | PUT/PATCH endpoints |
| `newmodule_approve` | `newmodule:approve` | Approval actions |

---

## Step 6: Write Tests

Create the test directory structure:

```text
cia-newmodule/src/test/java/com/nubeero/cia/newmodule/
├── NewModuleServiceTest.java       # unit — mock repo
└── NewEntityRepositoryIT.java      # integration — Testcontainers
```

See [Testing](./testing) for patterns.

---

## Dependency Rules

The module dependency graph is strictly layered. Violations cause circular dependency failures at build time.

| ✅ Allowed | ❌ Not allowed |
| --- | --- |
| Business module → `cia-common` | Business module → business module |
| Business module → `cia-workflow` | `cia-common` → any business module |
| Business module → `cia-notifications` | `cia-api` → external systems directly |
| `cia-partner-api` → any business module | Business module → `cia-partner-api` |
| `cia-api` → all modules | `cia-partner-api` → `cia-api` |

If two business modules need to share a type, move that type to `cia-common`.
