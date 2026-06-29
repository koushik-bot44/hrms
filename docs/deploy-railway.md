# Deploying the CDPP API to Railway (from GitHub)

The **API + PostgreSQL** run on Railway; the **web app** belongs on Vercel
(`apps/web`, Root Directory = `apps/web`, set `NEXT_PUBLIC_API_URL` to the API's
Railway URL). This doc covers the API + DB.

The repo is already deploy-ready:
- [`railway.json`](../railway.json) defines the build, start, and healthcheck.
- `apps/api/prisma/migrations/` holds the committed `init` migration; the start
  command runs `prisma migrate deploy` against the database on every deploy.

## ⚠️ Monorepo: keep Root Directory = repo root

This is a pnpm workspace and the API depends on the `@cdpp/shared` package. **Do
NOT** set the service's Root Directory to `apps/api` — that hides the workspace
root and `@cdpp/shared`, breaking the build. Leave Root Directory empty (repo root).
`railway.json` lives at the repo root for the same reason.

## One-time dashboard setup

1. **New Project** in Railway.
2. **Add PostgreSQL**: *New → Database → Add PostgreSQL*.
3. **Add the API service**: *New → GitHub Repo* → authorize the Railway GitHub App
   on **`salyushchavas/DocProv-Platform`** → select it. Railway reads `railway.json`
   automatically.
4. **Service Variables** (API service → Variables):
   - `DATABASE_URL` = `${{Postgres.DATABASE_URL}}`  ← reference the Postgres service
   - `NODE_ENV` = `production`
   - `PORT` is injected by Railway automatically — **do not set it**.
   - Optional / later: `CORS_ORIGINS` = your Vercel web URL. The rest
     (`JWT_SECRET`, `REFRESH_SECRET`, `S3_*`, `OIDC_*`, `BGV_*`) may stay empty —
     env validation accepts empty placeholders.
5. **Deploy.** The healthcheck polls `GET /health` (returns 200 with
   `{ "status": "ok", "db": "up" }`).
6. **Generate a public domain**: API service → *Settings → Networking → Generate
   Domain*. That URL is your API base — put it in Vercel as `NEXT_PUBLIC_API_URL`.

## Seed (one-time, optional)

The schema is applied by `migrate deploy`, but the seed (Entity `STELLAR` + one HR
operator) is not run automatically. After the first successful deploy, run it once:

```
railway run --service <api-service> pnpm --filter api exec prisma db seed
```

(or use the service's *Run a command* in the dashboard).

## What `railway.json` runs

- **Build:** `pnpm --filter @cdpp/shared build && pnpm --filter api exec prisma generate && pnpm --filter api build`
  (Nixpacks runs `pnpm install` first, using the `packageManager: pnpm@9.15.0`
  field via corepack.)
- **Start:** `prisma migrate deploy && pnpm --filter api start` (`node dist/main.js`,
  bound to `0.0.0.0`).
- **Healthcheck:** `/health`.

## Verified locally

The exact prod path was validated against a throwaway Postgres: empty DB →
`prisma migrate deploy` applied `init` → seed → API booted on `0.0.0.0` →
`GET /health` returned `{"status":"ok","db":"up"}` with the seed row present.

## Local development

```
cp apps/api/.env.example apps/api/.env   # set DATABASE_URL
pnpm --filter api exec prisma migrate deploy
pnpm --filter api exec prisma db seed
pnpm --filter api dev
```
