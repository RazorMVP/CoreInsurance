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

In demo mode the app uses mocked auth (`DevAuthProvider`) and **never reads `VITE_KEYCLOAK_URL`**.

### Now — nothing to set in Vercel (the demo flag lives in the workflow)

**`VITE_DEMO_MODE: 'true'` is baked into the workflow's `Build (production)` and `Build (preview)`
steps** (`.github/workflows/vercel-deploy-platform.yml`), NOT as a Vercel dashboard env var. This is
deliberate: the dashboard path is error-prone — a `VITE_DEMO_MODE` var with an **empty value** silently
ships a **blank page** (the `main.tsx` guard throws because `"" === 'true'` is false, and Vercel's CLI
`env add` proved unreliable at storing the value). Baking it in the workflow is deterministic and
version-controlled.

So for a demo deploy: **do not add any `VITE_DEMO_MODE` env var in the Vercel dashboard.** If one
exists, **delete it** — a Vercel-pulled env var overrides the workflow's value, so an empty one would
re-break the build. Leave `VITE_API_BASE_URL`, `VITE_KEYCLOAK_URL`, `VITE_KEYCLOAK_REALM`,
`VITE_KEYCLOAK_CLIENT_ID` unset too. Data calls to `/api/v1/platform/**` will fail (no backend) and list
pages land in their empty states — the honest frontend-only-demo posture.

> **Verify the build actually baked it in.** After a deploy, the served JS must **not** contain the
> string `VITE_KEYCLOAK_URL is required` (guard eliminated ⇒ demo on) and **should** contain
> `Stakeholder preview` (the demo banner). One-liner:
> `A=$(curl -s URL | grep -oE '/assets/index-[^"]+\.js' | head -1); curl -s "URL$A" | grep -c "Stakeholder preview"` — expect `1`.

### Later — when real `platform` Keycloak + backend are deployed

1. **Remove the two `VITE_DEMO_MODE: 'true'` lines** from the workflow's `Build (production)` +
   `Build (preview)` steps (so the production guard re-arms — a non-demo build with no Keycloak URL will
   correctly fail rather than silently mock auth).
2. Add these as **Vercel** env vars (Production, and Preview if desired):

| Key | Value (example) |
|---|---|
| `VITE_API_BASE_URL` | `https://api.<your-domain>` (the cia-api host serving `/api/v1/platform/**`) |
| `VITE_KEYCLOAK_URL` | `https://auth.<your-domain>` — your **deployed** Keycloak base (NOT `localhost:8280`, which only works for local `pnpm dev`) |
| `VITE_KEYCLOAK_REALM` | `platform` |
| `VITE_KEYCLOAK_CLIENT_ID` | `cia-platform` |

> Set these via the **Vercel dashboard UI**, not `vercel env add` (which silently stored empty values in
> this CLI version). After adding, confirm with `vercel pull --environment=production` →
> `grep VITE_ .vercel/.env.production.local` shows the real values, not `""`.

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

**`Error: No Output Directory named "dist" found` at the Build step.** This is the monorepo output-path
gotcha and is already fixed in the committed `vercel.json`: **`outputDirectory` is `dist`, not
`apps/platform/dist`.** Reason: the workflow runs `vercel build` with `working-directory:
cia-frontend/apps/platform`, so the committed `apps/platform/vercel.json` IS the project root and
`outputDirectory` is interpreted **relative to the app dir** — `pnpm --filter @cia/platform build`
emits to `apps/platform/dist`, which is `dist` from that working directory. (Back-office doesn't hit
this because *its* `vercel.json` lives at `cia-frontend/`, one level up, and isn't read by its own
`vercel build` — it relies on dashboard settings instead.) `installCommand`/`buildCommand` need no
change: pnpm walks up from `apps/platform` to find the workspace root, so workspace packages resolve.

**Production build throws `VITE_KEYCLOAK_URL is required…`.** Working as designed — that environment is
not in demo mode and has no Keycloak URL. Either set `VITE_DEMO_MODE=true` there, or finish the "Later"
half of Step 2 with a real deployed Keycloak.

**Empty/erroring lists on the demo URL.** Expected — demo mode has no backend, so `/api/v1/platform/**`
calls fail. The UI shell, layout, and forms still render; only live data is absent.
