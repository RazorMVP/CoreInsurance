# S5.3 — Server pagination: Claims / Customers / Audit lists

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Bring the last three phase-1 lists (Claims, Customers, Audit log) onto true server-side pagination + filter + sort, closing the `list-endpoints-true-pagination` backlog row and finishing the S5 phase-1 set (9 lists).

**Architecture:** Mirror the merged S5.2 pattern exactly (`PolicySpecs`/`PolicyService.list`/`PolicyController` + `PolicyListPage` + `UnderwritingWebItSupport`/`PolicyListControllerIT`, all on `main`). Two backend stacks get a `JpaSpecificationExecutor` + null-returning `*Specs` factory (Claims, Customer); **Audit needs no backend change** (its `AuditLogController.search(AuditLogFilter, Pageable)` + `AuditQueryService.buildSpec` already filter+paginate server-side). Claims additionally gets a small `GET /api/v1/claims/stats` aggregate endpoint because its dashboard StatCards (`open`/`totalReserve`/`totalApproved`) sum the whole list, which paging breaks. Frontend: each page adopts `useServerPagination` + `validatedList` + the DataTable `serverPagination` (sort) + toolbar server-search from S5.2.

**Tech Stack:** Spring Boot 3.5 / Java 21 (JPA Specification) · React + TanStack Query/Table + Zod + `@cia/ui`.

## Global Constraints

- **Mirror the S5.2 templates verbatim** (swap entity/enum/columns): `cia-policy/.../PolicySpecs.java`, `PolicyService.list(...)`, `PolicyController` `q` param, `cia-api/src/test/.../underwriting/UnderwritingWebItSupport.java`, `.../policy/PolicyListControllerIT.java`, and FE `policy/pages/PolicyListPage.tsx`. Read them first.
- Keep the existing `/claims/search` + `/customers/search` endpoints; don't change the `{data,meta}` envelope or `@PageableDefault(size=2000)`.
- **ITs:** `@WithMockUser(authorities={"ROLE_CLAIMS_VIEW"})` / `{"ROLE_CUSTOMER_VIEW"}` — the controllers gate on `hasRole(...)` (ROLE_ prefix required, as in S5.2). Audit uses `hasAnyRole('AUDIT_VIEW','SETUP_UPDATE')` → `ROLE_AUDIT_VIEW`.
- **NDPR:** Customer `q` may LIKE only the **plain** columns (`customerNumber,firstName,lastName,email,phone`) — never `idNumber`/`address` (`@ColumnTransformer` pgcrypto bytea, not substring-searchable).
- FE: `check-api-wiring` (no console.log / no top-level mock) + `check-dto-drift` + `pnpm --filter @cia/back-office build` + Vitest all pass. Disable server-sort on computed columns (`enableSorting:false`).
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## File Structure

**Backend (create):** `cia-claims/.../ClaimSpecs.java`, `cia-claims/.../dto/ClaimStatsResponse.java`, `cia-customer/.../CustomerSpecs.java`, `cia-api/src/test/.../claims/ClaimListControllerIT.java`, `cia-api/src/test/.../customer/CustomerListControllerIT.java`.
**Backend (modify):** `ClaimRepository`, `ClaimService`, `ClaimController` (cia-claims); `CustomerRepository`, `CustomerService`, `CustomerController` (cia-customer).
**Frontend (modify):** `packages/api-client/src/modules/claim.ts` (+`ClaimStatsDto`+schema), `apps/back-office/src/modules/claims/pages/ClaimsListPage.tsx`, `.../customers/pages/CustomersListPage.tsx`, `.../audit/pages/audit-log/AuditLogTab.tsx`.

---

### Task 1: Backend — Claims Specs + `q` + stats endpoint + IT

**Files:** Create `ClaimSpecs.java`, `dto/ClaimStatsResponse.java`, `cia-api/.../claims/ClaimListControllerIT.java`; modify `ClaimRepository`, `ClaimService`, `ClaimController`.

**Interfaces produced:** `GET /api/v1/claims?q=&status=&page=&size=&sort=` (combinable) · `GET /api/v1/claims/stats → {openCount:long, totalReserve:BigDecimal, totalApproved:BigDecimal}`.

- [ ] **Step 1:** `ClaimSpecs` — copy `PolicySpecs.java` verbatim, rename `Policy`→`Claim`, keep `notDeleted()`, `statusEquals(ClaimStatus)`, `customerIdEquals(UUID)`, add `policyIdEquals(UUID)`, and `qLike` over `claimNumber, customerName, policyNumber` (Claim's denormalised columns). Confirm `ClaimStatus` import (`com.nubeero.cia.claims.ClaimStatus`).
- [ ] **Step 2:** `ClaimRepository extends JpaRepository<Claim,UUID>, JpaSpecificationExecutor<Claim>` (add the interface + import). Keep the existing derived + `@Query` methods.
- [ ] **Step 3:** `ClaimService.list(ClaimStatus status, UUID policyId, UUID customerId, String q, Pageable)` composing `Specification.where(ClaimSpecs.notDeleted()).and(statusEquals(status)).and(policyIdEquals(policyId)).and(customerIdEquals(customerId)).and(qLike(q))` then `.map(this::toResponse)` (mirror `PolicyService.list`). Keep `search(...)`.
- [ ] **Step 4:** `ClaimController.list(...)` — add `@RequestParam(required=false) String q`, pass to `service.list(status, policyId, customerId, q, pageable)`.
- [ ] **Step 5:** `ClaimStatsResponse` — `@Data @Builder` (or record) with `long openCount; BigDecimal totalReserve; BigDecimal totalApproved;`.
- [ ] **Step 6:** `ClaimService.stats()` — a `@Transactional(readOnly=true)` aggregate. Add to `ClaimRepository` a JPQL projection query:

```java
@Query("""
    SELECT COALESCE(SUM(CASE WHEN c.status NOT IN
              (com.nubeero.cia.claims.ClaimStatus.SETTLED,
               com.nubeero.cia.claims.ClaimStatus.WITHDRAWN) THEN 1 ELSE 0 END), 0) AS openCount,
           COALESCE(SUM(c.reserveAmount), 0)  AS totalReserve,
           COALESCE(SUM(c.approvedAmount), 0) AS totalApproved
    FROM Claim c WHERE c.deletedAt IS NULL""")
ClaimStatsProjection stats();
```

with an interface projection `interface ClaimStatsProjection { long getOpenCount(); BigDecimal getTotalReserve(); BigDecimal getTotalApproved(); }` (Spring Data derives it). Service maps projection → `ClaimStatsResponse`. (Verify the `open` semantics against the FE: `open = claims.filter(c => !['SETTLED','WITHDRAWN'].includes(c.status)).length` — matched above.)
- [ ] **Step 7:** `ClaimController` — `@GetMapping("/stats") @PreAuthorize("hasRole('CLAIMS_VIEW')")` returning `ApiResponse.success(service.stats())`.
- [ ] **Step 8: IT** `cia-api/.../claims/ClaimListControllerIT` extends `UnderwritingWebItSupport` (reuse it — it already mocks the Temporal/storage/JWT beans; if its authorities are `@WithMockUser`-per-method, set class-level `@WithMockUser(authorities={"ROLE_CLAIMS_VIEW"})`). Seed 25 claims via `JdbcTemplate` (copy the `INSERT INTO claims(...)` column list from an existing claims `@DataJpaTest` IT; `claim_number,customer_name,policy_number,status,reserve_amount` + NOT-NULL cols). Tests: pagination-meta (`?page=0&size=10` → 10 rows, `meta.total>=25`); q-narrows; status+q combine; `?sort=claimNumber,desc`; **stats** (`GET /claims/stats` → `openCount`/`totalReserve`/`totalApproved` match the seeded rows); 403 wrong-role.
- [ ] **Step 9:** Verify: `mvn -q install -DskipTests -pl cia-claims -am && mvn -q verify -pl cia-api -Dit.test=ClaimListControllerIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false` → green. Commit `feat(claims): server-side search/filter + stats endpoint on claims list`.

---

### Task 2: Backend — Customer Specs + `q` + status filter + IT

**Files:** Create `CustomerSpecs.java`, `cia-api/.../customer/CustomerListControllerIT.java`; modify `CustomerRepository`, `CustomerService`, `CustomerController`.

**Interfaces produced:** `GET /api/v1/customers?q=&type=&kycStatus=&status=&page=&size=&sort=`.

- [ ] **Step 1:** `CustomerSpecs` — copy `PolicySpecs.java`, rename to `Customer`; factories `notDeleted()`, `typeEquals(CustomerType)`, `kycStatusEquals(KycStatus)`, `customerStatusEquals(CustomerStatus)`, and `qLike` over **plain columns only**: `customerNumber, firstName, lastName, email, phone` (NOT idNumber/address — encrypted). Confirm enum imports (`CustomerType`, `KycStatus`, `CustomerStatus` in `com.nubeero.cia.customer`).
- [ ] **Step 2:** `CustomerRepository extends … JpaSpecificationExecutor<Customer>`.
- [ ] **Step 3:** `CustomerService.list(CustomerType type, KycStatus kycStatus, CustomerStatus status, String q, Pageable)` composing the Specs → `.map(this::toSummary)` (find the existing summary mapper). Keep `search(...)`.
- [ ] **Step 4:** `CustomerController.list(...)` — add `@RequestParam(required=false) CustomerStatus status` + `@RequestParam(required=false) String q`; pass through.
- [ ] **Step 5: IT** `CustomerListControllerIT` extends `UnderwritingWebItSupport`, `@WithMockUser(authorities={"ROLE_CUSTOMER_VIEW"})`. Seed 25 customers (copy INSERT cols from an existing customer `@DataJpaTest` IT — note encrypted cols use `pgp_sym_encrypt(...)`; seed `customer_number,first_name,last_name,email,phone,customer_status,kyc_status,customer_type`). Tests: pagination-meta; q-narrows (on email/name); status+q combine; `?sort=customerNumber,desc`; **q does NOT match on encrypted address** (seed a row whose address contains the search term, assert it's absent); 403 wrong-role.
- [ ] **Step 6:** Verify `mvn -q install -DskipTests -pl cia-customer -am && mvn -q verify -pl cia-api -Dit.test=CustomerListControllerIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false` → green. Commit `feat(customer): server-side search (plain cols) + status filter on customer list`.

---

### Task 3: Frontend — api-client `ClaimStatsDto`

**Files:** Modify `packages/api-client/src/modules/claim.ts`.

- [ ] **Step 1:** Add
```ts
export const ClaimStatsDtoSchema = z.object({
  openCount:     z.number(),
  totalReserve:  z.number(),
  totalApproved: z.number(),
});
export type ClaimStatsDto = z.infer<typeof ClaimStatsDtoSchema>;
```
(`ClaimDtoSchema` already exists from S4a — reused for the list.) Run `node cia-frontend/scripts/check-dto-drift.mjs` → clean (ClaimStats has no backend `*Response` counterpart named `ClaimStatsDto`→`ClaimStatsResponse`; if drift-guard flags it, the names match `ClaimStatsResponse` so it maps — verify field set matches). Commit `feat(api-client): ClaimStatsDto`.

---

### Task 4: Frontend — ClaimsListPage → server pagination + stats

**Files:** Modify `claims/pages/ClaimsListPage.tsx`. **Mirror `PolicyListPage.tsx`** (the merged S5.2 page) for the pagination/sort/search/status-filter shell.

- [ ] **Step 1:** Imports — add `useServerPagination`, `useDebouncedValue`, `validatedList` + `ClaimStatsDtoSchema`, `Select*`; drop `validatedGet`+`z.array` for the list. Add `const CLAIM_STATUSES = [...] as const` (from the `statusVariant` keys).
- [ ] **Step 2:** State (mirror PolicyListPage):
```tsx
const { page, size, sort, filters, setPage, setSize, setSort, setFilter } =
  useServerPagination({ defaultSize: 20, defaultSort: 'createdAt,desc' });
const status = filters.status ?? '';
const [searchInput, setSearchInput] = useState(filters.q ?? '');
const debouncedSearch = useDebouncedValue(searchInput, 300);
useEffect(() => { setFilter('q', debouncedSearch); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [debouncedSearch]);

const claimsQuery = useQuery({
  queryKey: ['claims', page, size, sort, status, filters.q ?? ''],
  queryFn: () => validatedList('/api/v1/claims', ClaimDtoSchema, {
    params: { page, size, sort, ...(status ? { status } : {}), ...(filters.q ? { q: filters.q } : {}) },
  }),
});
const claims = claimsQuery.data?.data ?? [];
const total  = claimsQuery.data?.meta.total ?? 0;

const statsQuery = useQuery({
  queryKey: ['claims', 'stats'],
  queryFn: () => validatedGet('/api/v1/claims/stats', ClaimStatsDtoSchema),
});
const stats = statsQuery.data;
```
- [ ] **Step 3:** StatCards read from `stats` (not the page array): `value={String(stats?.openCount ?? 0)}`, `₦${(stats?.totalReserve ?? 0).toLocaleString()}`, `₦${(stats?.totalApproved ?? 0).toLocaleString()}`. Remove the old `open`/`reserved`/`approved` `.filter`/`.reduce` lines. The "N total" sub becomes `${total} total`.
- [ ] **Step 4:** Status filter `Select` in the header actions (mirror PolicyListPage: `value={status||'ALL'}`, `onValueChange={(v)=>setFilter('status', v==='ALL'?'':v)}`, options from `CLAIM_STATUSES`). Empty-state guard: `total===0 && !status && !filters.q`.
- [ ] **Step 5:** DataTable: `toolbar={{ searchPlaceholder:'Search claims…', searchValue:searchInput, onSearchChange:setSearchInput }}` + `serverPagination={{ page,size,total,onPageChange:setPage,onSizeChange:setSize,sort,onSortChange:setSort }}`. Sortable columns use accessorKeys that are entity props (`claimNumber`,`customerName`,`status`,`createdAt`); set `enableSorting:false` on any computed column.
- [ ] **Step 6:** `pnpm --filter @cia/back-office build` + `check-api-wiring` → pass. Commit `feat(ui): server pagination + server-computed StatCards on Claims list`.

---

### Task 5: Frontend — CustomersListPage → server pagination

**Files:** Modify `customers/pages/CustomersListPage.tsx`. Mirror PolicyListPage; **no StatCards** here (simpler).

- [ ] **Step 1:** Same import/state swap as Task 4 (minus stats). `validatedList('/api/v1/customers', CustomerSummaryDtoSchema, { params: { page,size,sort, ...(status?{status}:{}) , ...(filters.q?{q:filters.q}:{}) } })`. `status = filters.status ?? ''` maps to the backend `customerStatus` filter param (name it `status` in the query — the controller's `@RequestParam CustomerStatus status`).
- [ ] **Step 2:** Status filter `Select` (ACTIVE/INACTIVE/BLACKLISTED) in header actions. Keep the blacklist mutation + `ConfirmDeleteDialog` untouched (still uses `apiClient.post`). Toolbar server-search (`searchValue/onSearchChange`). `serverPagination` with sort. Empty-state guard `total===0 && !status && !filters.q`.
- [ ] **Step 3:** build + guards → pass. Commit `feat(ui): server pagination + search/filter on Customers list`.

---

### Task 6: Frontend — AuditLogTab → server filters (no backend change)

**Files:** Modify `audit/pages/audit-log/AuditLogTab.tsx`. Audit backend already server-filters via `AuditLogFilter` (`entityType,entityId,userId,action,from,to`) + `ApiMeta`.

- [ ] **Step 1:** Replace the `useQuery<AuditLogDto[]>(validatedGet(...))` + client `useMemo` filter with `useServerPagination` + `validatedList('/api/v1/audit/logs', AuditLogDtoSchema, { params })`. Map the existing filter controls to the backend param names:
```tsx
const { page, size, sort, filters, setPage, setSize, setSort, setFilter } =
  useServerPagination({ defaultSize: 20, defaultSort: 'timestamp,desc' });
// existing Select/inputs now drive filters via setFilter, keyed to the backend AuditLogFilter:
//   entityType → 'entityType' | action → 'action' | user → 'userId' | entityId → 'entityId'
//   dateFrom → 'from' (append 'T00:00:00Z') | dateTo → 'to' (append 'T23:59:59Z')
const params = {
  page, size, sort,
  ...(filters.entityType && filters.entityType !== 'ALL' ? { entityType: filters.entityType } : {}),
  ...(filters.action     && filters.action     !== 'ALL' ? { action: filters.action } : {}),
  ...(filters.userId   ? { userId: filters.userId } : {}),
  ...(filters.entityId ? { entityId: filters.entityId } : {}),
  ...(filters.from ? { from: `${filters.from}T00:00:00Z` } : {}),
  ...(filters.to   ? { to:   `${filters.to}T23:59:59Z` } : {}),
};
const auditQuery = useQuery({ queryKey: ['audit','logs', params], queryFn: () => validatedList('/api/v1/audit/logs', AuditLogDtoSchema, { params }) });
const rows  = auditQuery.data?.data ?? [];
const total = auditQuery.data?.meta.total ?? 0;
```
- [ ] **Step 2:** Rewire the existing filter `Select`/`Input` `value`/`onChange` from local `useState` to `filters.*` / `setFilter(...)` (entityType, action, userId, entityId, from, to). Delete the `filtered = useMemo(...)` client filter; the DataTable now renders `rows` directly with `serverPagination`. The CSV export (`exportCSV`) — note it now exports only the current page; add a `log()`-style comment or (optional) leave a follow-up for a server-export (out of scope; the `/audit/export` controller already exists — a P3 note).
- [ ] **Step 3:** build + guards → pass. Commit `feat(ui): drive Audit log filters + pagination server-side`.

---

### Task 7: Frontend — regression test

**Files:** Create `apps/back-office/src/modules/claims/claims-list-server.test.tsx` (or an envelope-parse test mirroring `setup-envelope-parse.test.ts`).

- [ ] **Step 1:** Mock `@cia/api-client` (`validatedList` returns `{data:[...],meta:{total,page,size}}`, `validatedGet` returns the stats object); assert ClaimsListPage (a) calls `validatedList('/api/v1/claims', …, {params:{page,size,sort,…}})`, (b) renders StatCards from the stats query (not the row array), (c) renders the rows. Mirror the FACTab `importOriginal`+DataTable-mock idiom.
- [ ] **Step 2:** `pnpm --filter @cia/back-office test` full suite green. Commit `test(claims): server-pagination + stats wiring`.

---

## Self-Review notes

- **Spec coverage:** Claims (T1 BE + T4 FE + stats), Customers (T2 BE + T5 FE), Audit (T6 FE-only — backend already server-side) — the 3 phase-1 lists. ✓
- **The stats endpoint** is the one net-new backend surface (not a mechanical mirror) — flagged for the plan-review gate. Everything else mirrors merged S5.2 templates.
- **NDPR:** Customer `q` restricted to plain columns; an IT asserts encrypted-address is NOT matched. ✓
- **Type consistency:** `useServerPagination`/`validatedList`/`serverPagination` used identically to the merged PolicyListPage. Audit maps FE control names → backend `AuditLogFilter` names (userId/from/to) — the one place names differ; called out in T6.
- **Backlog:** on merge, **removes `list-endpoints-true-pagination`** (phase-1 set complete: 3 refactors + 9 lists). Note the nested detail-feeds (claim comments/docs/reserves) stay on the raised cap by design (S5 spec §out-of-scope). Add a P3 row for audit CSV server-export (currently exports current page only).
