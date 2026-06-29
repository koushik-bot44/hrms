# Deploying the web app to Vercel (from GitHub)

`apps/web` → **Vercel**; `apps/api` + Postgres → **Railway** (see
[deploy-railway.md](./deploy-railway.md)). With the GitHub integration, **every push
to `main` auto-redeploys** both.

## Import into Vercel

1. Vercel → **Add New → Project** → import **`salyushchavas/DocProv-Platform`**.
2. **Root Directory: `apps/web`**.
3. Framework preset: **Next.js** (auto-detected).
4. Build: handled by [`apps/web/vercel.json`](../apps/web/vercel.json) — it builds
   `@cdpp/shared` first, then `next build`. (pnpm + the workspace are auto-detected;
   install runs at the repo root, so `@cdpp/shared` is available.)
5. **Environment Variables:**
   - `NEXT_PUBLIC_API_URL` = `https://<your-railway-api-domain>`
6. **Deploy.**

## Gotchas

- **`NEXT_PUBLIC_API_URL` is inlined at *build* time.** Set it once the Railway API
  has a public domain, then redeploy (or push) so the API-status indicator and API
  client actually reach the API.
- After Vercel assigns the web a URL, set **`CORS_ORIGINS`** on the Railway `api`
  service to that URL (comma-separated if several) so the browser can call `/health`.
- Don't deploy `apps/web` on Railway — web lives on Vercel; Railway runs only `api`
  (+ Postgres).
