# Partner Portal — Vercel Deploy Runbook

One-time setup to put the Sub-project B Partner Portal SPA (`apps/partner`) live on its own
Vercel project. The CI workflow (`.github/workflows/vercel-deploy-partner.yml`) and
`apps/partner/vercel.json` are already committed; everything below is the human
dashboard/secret setup that code can't do.

> **Status:** the public partner URL is a **frontend-only demo** until the `partner` Keycloak
> realm + backend BFF (`PortalAuthController` / `cia.partner-portal.*`, Sub-project A) are live for
> this deployment — exactly like back-office's and platform's public previews. Deploy it in
> **demo mode** now; wire real (cookie-session) auth later (see Step 2 → "Later").

---

## Prerequisites (already in place)

- `cia-frontend/apps/partner/vercel.json` + `.github/workflows/vercel-deploy-partner.yml` — committed.
- GitHub repo secrets **`VERCEL_TOKEN`** and **`VERCEL_ORG_ID`** already exist (shared with
  back-office + platform). You add only **`VERCEL_PARTNER_PROJECT_ID`**.
- Access to the Vercel team `team_7FziB9JbVAXmjPfdIdf5aO19` (hosts back-office + platform).

How it deploys: the workflow runs `vercel pull / build / deploy` from `cia-frontend/apps/partner`,
linking to the project via the `VERCEL_PROJECT_ID` **env var** (= the `VERCEL_PARTNER_PROJECT_ID`
secret). `vercel pull` downloads the project's build settings **and env vars from the Vercel
dashboard** — so env vars must live in the Vercel project, not in GitHub. No `.vercel/` directory
is committed for the partner app.

---

## Step 1 — Create the third Vercel project (get its project ID)

**Path A — Dashboard (recommended):**

1. **vercel.com → your team → Add New ▸ Project**.
2. **Import** `RazorMVP/CoreInsurance`. It will warn the repo is already connected to back-office
   (and platform) — continue (one repo can back multiple projects).
3. **Project Name:** `cia-partner` (→ default URL `cia-partner.vercel.app`).
4. **Root Directory:** **`cia-frontend`** (click *Edit* → pick the folder). Mirrors back-office/
   platform; lets pnpm resolve workspace packages.
5. **Framework Preset:** Vite.
6. **Build & Output Settings** → override to match `apps/partner/vercel.json`:
   - **Build Command:** `pnpm --filter @cia/partner build`
   - **Output Directory:** `apps/partner/dist`
   - **Install Command:** `pnpm install --frozen-lockfile`

   This dashboard override uses the **app-relative** path (`apps/partner/dist`) because the
   dashboard's own git-integration build runs from the Root Directory (`cia-frontend`). The
   **committed** `apps/partner/vercel.json` instead says `outputDirectory: dist` — that file is
   read by the **CI workflow's** `vercel build`, which runs with `working-directory:
   cia-frontend/apps/partner`, making the app dir itself the project root (see Troubleshooting).
   Both are correct for their own code path; they are not the same string by design.
7. **Don't click Deploy yet** — add env vars (Step 2) first, or you'll trip the production guard.
8. **Settings ▸ General ▸ Project ID** → copy the `prj_…` value. That's your
   `VERCEL_PARTNER_PROJECT_ID`.

**Path B — CLI:**

```bash
npm i -g vercel                      # if needed
cd cia-frontend/apps/partner
vercel login                         # once
vercel link --project cia-partner    # answer Root Directory: ../..  (i.e. cia-frontend)
cat .vercel/project.json             # copy "projectId": "prj_…"
rm -rf .vercel                       # do NOT commit; CI links via the secret
```

---

## Step 2 — Set env vars (demo-only now, real auth deferred)

In demo mode the app uses the built-in `portal-mocks.ts` mock adapter and never calls the real
`/portal/**` BFF endpoints.

### Now — nothing to set in Vercel (the demo flag lives in the workflow)

**`VITE_DEMO_MODE: 'true'` is baked into the workflow's `Build (production)` and `Build (preview)`
steps** (`.github/workflows/vercel-deploy-partner.yml`), NOT as a Vercel dashboard env var. This is
deliberate — mirrors platform's rationale: the dashboard path is error-prone (an empty-value env
var silently ships a broken build), so baking it into the workflow is deterministic and
version-controlled.

So for a demo deploy: **do not add any `VITE_DEMO_MODE` env var in the Vercel dashboard.** If one
exists, **delete it** — a Vercel-pulled env var overrides the workflow's value. Leave
`VITE_API_BASE_URL` unset too. Data calls will use mocked data via `portal-mocks.ts`; the P1/P2/P3/P5
pages render fully against realistic mock shapes — the honest frontend-only-demo posture.

### Later — when the real BFF + `partner` Keycloak realm are deployed

1. **Remove the two `VITE_DEMO_MODE: 'true'` lines** from the workflow's `Build (production)` +
   `Build (preview)` steps (so the app stops using `portal-mocks.ts` and calls the real `/portal/**`
   endpoints via the credentialed axios client).
2. Add this as a **Vercel** env var (Production, and Preview if desired):

   | Key | Value (example) |
   |---|---|
   | `VITE_API_BASE_URL` | `https://api.<your-domain>` (the cia-api / BFF host serving `/portal/**`) |

   Set it via the **Vercel dashboard UI**, not `vercel env add` (see platform's DEPLOY.md note on
   CLI-stored empty values). After adding, confirm with
   `vercel pull --environment=production` → `grep VITE_ .vercel/.env.production.local`.
3. **Infra prerequisite, not code:** the Partner Portal is **cookie-session** against the BFF
   (`PortalAuthController`), not a Bearer-token SPA — the browser never holds a token or secret
   except the one-time rotate-secret reveal. For real mode to work cross-origin, the deployed
   partner Vercel origin (e.g. `https://cia-partner.vercel.app`) **must** be added to
   `cia.partner-portal.allowed-origins` (env `CIA_PARTNER_PORTAL_ALLOWED_ORIGINS`) on the BFF
   deployment — otherwise the credentialed cookie requests are blocked by CORS. This is a
   Sub-project A / backend infra setting; nothing in this repo's frontend code changes for it.
   The `partner` Keycloak realm + `cia-partner-portal` client must also exist (provisioned by
   enabling the gated `PartnerPortalBootstrapRunner` per the main `CLAUDE.md` env-var table).

---

## Step 3 — Add the GitHub secret

**GitHub → repo Settings ▸ Secrets and variables ▸ Actions ▸ New repository secret:**
- **Name:** `VERCEL_PARTNER_PROJECT_ID`
- **Value:** the `prj_…` from Step 1.8.

`VERCEL_TOKEN` and `VERCEL_ORG_ID` already exist (back-office + platform use them). The partner
workflow references exactly these three (`VERCEL_PROJECT_ID` = `secrets.VERCEL_PARTNER_PROJECT_ID`).

**CLI alternative:**

```bash
gh secret set VERCEL_PARTNER_PROJECT_ID --body "prj_xxxxxxxx" --repo RazorMVP/CoreInsurance
```

---

## Step 4 — Avoid double-deploys (one decision)

`vercel-deploy.yml` (back-office), `vercel-deploy-platform.yml`, and `vercel-deploy-partner.yml`
all fire on any `cia-frontend/**` change, and each runs its own `vercel deploy`. To stop Vercel's
**native Git integration** from *also* auto-deploying the partner project, in
**cia-partner ▸ Settings ▸ Git** either:
- **Disconnect** the Git repo (deploys happen only via the Actions workflow — cleanest), or
- set **Ignored Build Step** to `exit 0` so the git-trigger no-ops and only the Actions
  `vercel deploy --prebuilt` produces a deployment.

Mirror whatever the back-office and platform projects do, for consistency.

---

## Step 5 — First deploy + verify

- **Preview:** push any `cia-frontend/**` change to a PR branch → the `Vercel Deploy — Partner
  Portal` workflow runs the preview path and links a `…-cia-partner.vercel.app` preview URL.
- **Production:** merging to `main` triggers `vercel deploy --prebuilt --prod`.
- **Check:** GitHub **Actions** tab → the partner workflow run is green; the deploy step prints
  the URL. Open it — with `VITE_DEMO_MODE=true` you should see the dark portal shell + demo
  banner, and Credentials / API Explorer / Webhooks / Usage all render against mocked data
  (`portal-mocks.ts`).

---

## Troubleshooting

**`Error: No Output Directory named "dist" found` at the Build step.** This is the monorepo
output-path gotcha (already hit and documented for platform) and is already fixed in the
committed `vercel.json`: **`outputDirectory` is `dist`, not `apps/partner/dist`.** Reason: the
workflow runs `vercel build` with `working-directory: cia-frontend/apps/partner`, so the committed
`apps/partner/vercel.json` IS the project root and `outputDirectory` is interpreted **relative to
the app dir** — `pnpm --filter @cia/partner build` emits to `apps/partner/dist`, which is `dist`
from that working directory. (Back-office doesn't hit this because *its* `vercel.json` lives at
`cia-frontend/`, one level up, and isn't read by its own `vercel build` — it relies on dashboard
settings instead.) `installCommand`/`buildCommand` need no change: pnpm walks up from
`apps/partner` to find the workspace root, so workspace packages resolve.

**Production build throws an API/auth error instead of rendering.** Working as designed if that
environment is not in demo mode and has no real `VITE_API_BASE_URL` / BFF reachable. Either set
`VITE_DEMO_MODE=true` there, or finish the "Later" half of Step 2 with a real deployed BFF +
`partner` Keycloak realm + the CORS allowlist entry.

**Empty/erroring lists on the real (non-demo) URL.** Check `CIA_PARTNER_PORTAL_ALLOWED_ORIGINS` on
the BFF includes this exact Vercel origin — a missing entry fails silently as a browser CORS
error, not a visible app error, since the credentialed cookie request never completes.
