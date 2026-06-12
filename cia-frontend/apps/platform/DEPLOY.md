# NubSure Platform Console — Vercel Deploy Runbook

One-time setup to put the SP2 platform-admin console (`apps/platform`) live on its own
Vercel project. The CI workflow (`.github/workflows/vercel-deploy-platform.yml`) and
`apps/platform/vercel.json` are already committed; everything below is the human
dashboard/secret setup that code can't do.

> **Status:** the public platform URL is a **frontend-only demo** until a real `platform`
> Keycloak realm + backend (`cia-api`) are deployed — exactly like back-office's public
> preview. Deploy it in **demo mode** now; wire real auth later (see Step 2 → "Later").

---

## Prerequisites (already in place)

- `cia-frontend/apps/platform/vercel.json` + `.github/workflows/vercel-deploy-platform.yml` — committed.
- GitHub repo secrets **`VERCEL_TOKEN`** and **`VERCEL_ORG_ID`** already exist (shared with back-office). You add only **`VERCEL_PLATFORM_PROJECT_ID`**.
- Access to the Vercel team `team_7FziB9JbVAXmjPfdIdf5aO19` (hosts back-office).

How it deploys: the workflow runs `vercel pull / build / deploy` from `cia-frontend/apps/platform`,
linking to the project via the `VERCEL_PROJECT_ID` **env var** (= the `VERCEL_PLATFORM_PROJECT_ID`
secret). `vercel pull` downloads the project's build settings **and env vars from the Vercel
dashboard** — so env vars must live in the Vercel project, not in GitHub. No `.vercel/` directory
is committed for the platform app.

---

## Step 1 — Create the second Vercel project (get its project ID)

**Path A — Dashboard (recommended):**

1. **vercel.com → your team → Add New ▸ Project**.
2. **Import** `RazorMVP/CoreInsurance`. It will warn the repo is already connected to back-office — continue (one repo can back multiple projects).
3. **Project Name:** `cia-platform` (→ default URL `cia-platform.vercel.app`).
4. **Root Directory:** **`cia-frontend`** (click *Edit* → pick the folder). Mirrors back-office; lets pnpm resolve workspace packages.
5. **Framework Preset:** Vite.
6. **Build & Output Settings** → override to match `apps/platform/vercel.json`:
   - **Build Command:** `pnpm --filter @cia/platform build`
   - **Output Directory:** `apps/platform/dist`
   - **Install Command:** `pnpm install --frozen-lockfile`
7. **Don't click Deploy yet** — add env vars (Step 2) first, or you'll trip the production guard.
8. **Settings ▸ General ▸ Project ID** → copy the `prj_…` value. That's your `VERCEL_PLATFORM_PROJECT_ID`.

**Path B — CLI:**

```bash
npm i -g vercel                       # if needed
cd cia-frontend/apps/platform
vercel login                          # once
vercel link --project cia-platform    # answer Root Directory: ../..  (i.e. cia-frontend)
cat .vercel/project.json              # copy "projectId": "prj_…"
rm -rf .vercel                        # do NOT commit; CI links via the secret
```

---

## Step 2 — Set env vars (demo-only now, Keycloak deferred)

In demo mode the app uses mocked auth (`DevAuthProvider`) and **never reads `VITE_KEYCLOAK_URL`**,
so set the minimum now and defer the rest until real infra exists.

### Now — the only var to add (cia-platform ▸ Settings ▸ Environment Variables)

| Key | Value | Environment | Why |
|---|---|---|---|
| `VITE_DEMO_MODE` | `true` | **Preview** (and Production too if you want a public demo at the prod URL) | Flips to `DevAuthProvider`, renders the dark console + amber "Demo" banner, and bypasses the production guard that throws on a missing `VITE_KEYCLOAK_URL`. |

Leave **`VITE_API_BASE_URL`, `VITE_KEYCLOAK_URL`, `VITE_KEYCLOAK_REALM`, `VITE_KEYCLOAK_CLIENT_ID` unset.**
Data calls to `/api/v1/platform/**` will fail (no backend reachable) and list pages land in their
empty/error states — the honest frontend-only-demo posture, same as back-office's public preview.

> **Do not deploy a non-demo Production build yet.** With `VITE_DEMO_MODE` unset and no Keycloak vars,
> `main.tsx` intentionally throws `VITE_KEYCLOAK_URL is required…` — a correct fail-safe that stops you
> shipping a tenant-facing console with no auth. Deploy Preview (demo) only, or set `VITE_DEMO_MODE=true`
> on Production for a public demo (what back-office does at `back-office-blush-six.vercel.app`).

### Later — when real `platform` Keycloak + backend are deployed

Add these and **remove `VITE_DEMO_MODE`** from that environment:

| Key | Value (example) |
|---|---|
| `VITE_API_BASE_URL` | `https://api.<your-domain>` (the cia-api host serving `/api/v1/platform/**`) |
| `VITE_KEYCLOAK_URL` | `https://auth.<your-domain>` — your **deployed** Keycloak base (NOT `localhost:8280`, which only works for local `pnpm dev`) |
| `VITE_KEYCLOAK_REALM` | `platform` |
| `VITE_KEYCLOAK_CLIENT_ID` | `cia-platform` |

The app deliberately reuses the `VITE_KEYCLOAK_*` names (no `VITE_PLATFORM_KEYCLOAK_*`) because
`@cia/auth`'s `initKeycloak` keys `onLoad:'login-required'` off `VITE_KEYCLOAK_URL`.

**Prereq:** the `platform` realm + `cia-platform` SPA client must exist in that Keycloak — provisioned
by enabling the gated `PlatformBootstrapRunner` (`cia.platform.bootstrap.enabled=true` +
`KEYCLOAK_ADMIN_ENABLED=true`), which calls `KeycloakTenantProvisioner.provisionPlatformRealm`.

---

## Step 3 — Add the GitHub secret

**GitHub → repo Settings ▸ Secrets and variables ▸ Actions ▸ New repository secret:**
- **Name:** `VERCEL_PLATFORM_PROJECT_ID`
- **Value:** the `prj_…` from Step 1.8.

`VERCEL_TOKEN` and `VERCEL_ORG_ID` already exist (back-office uses them). The platform workflow
references exactly these three (`VERCEL_PROJECT_ID` = `secrets.VERCEL_PLATFORM_PROJECT_ID`).

**CLI alternative:**

```bash
gh secret set VERCEL_PLATFORM_PROJECT_ID --body "prj_xxxxxxxx" --repo RazorMVP/CoreInsurance
```

---

## Step 4 — Avoid double-deploys (one decision)

Both `vercel-deploy.yml` (back-office) and `vercel-deploy-platform.yml` fire on any `cia-frontend/**`
change, and each runs its own `vercel deploy`. To stop Vercel's **native Git integration** from *also*
auto-deploying the platform project, in **cia-platform ▸ Settings ▸ Git** either:
- **Disconnect** the Git repo (deploys happen only via the Actions workflow — cleanest), or
- set **Ignored Build Step** to `exit 0` so the git-trigger no-ops and only the Actions
  `vercel deploy --prebuilt` produces a deployment.

Mirror whatever the back-office project does, for consistency.

---

## Step 5 — First deploy + verify

- **Preview:** push any `cia-frontend/**` change to a PR branch → the `Vercel Deploy — NubSure Platform`
  workflow runs the preview path and links a `…-cia-platform.vercel.app` preview URL.
- **Production:** merging to `main` triggers `vercel deploy --prebuilt --prod`.
- **Check:** GitHub **Actions** tab → the platform workflow run is green; the deploy step prints the URL.
  Open it — with `VITE_DEMO_MODE=true` you should see the dark console + amber "Demo" banner, and the
  Dashboard / Tenants / Audit / Super-admins screens render against mocked auth.

---

## Troubleshooting

**Build fails on the monorepo paths.** `apps/platform/vercel.json` assumes the project
**Root Directory = `cia-frontend`** (so `outputDirectory: apps/platform/dist` resolves and pnpm finds
the workspace root). If you instead set Root Directory = `cia-frontend/apps/platform`, change the output
to `dist` and enable **Settings ▸ General ▸ "Include files outside the root directory"**. Mirroring
back-office (Root = `cia-frontend`) is the safe choice and needs no `vercel.json` change.

**Production build throws `VITE_KEYCLOAK_URL is required…`.** Working as designed — that environment is
not in demo mode and has no Keycloak URL. Either set `VITE_DEMO_MODE=true` there, or finish the "Later"
half of Step 2 with a real deployed Keycloak.

**Empty/erroring lists on the demo URL.** Expected — demo mode has no backend, so `/api/v1/platform/**`
calls fail. The UI shell, layout, and forms still render; only live data is absent.
