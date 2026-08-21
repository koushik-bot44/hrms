# Runbook — set / rotate `FIELD_ENC_KEY` without losing encrypted data

The sensitive columns (`employees.aadhaarNumber`, `form1_personal.offeredCtc`, `form2_info.panNumber`,
`form2_info.axisAccountNumber`, `form3_prev_employment.lastDrawnSalary`) are AES-256-GCM encrypted with a key
derived from `FIELD_ENC_KEY` (§6). If `FIELD_ENC_KEY` was ever **unset**, those values were written under the
**committed public dev key** — effectively unprotected — and the prod boot guard now refuses to start until a
real key is set.

Because AES-GCM is keyed, existing values written under one key **cannot be read under a different key**. Set a
real key with the one-time re-encryption below so nothing is lost.

## When existing data must be preserved (the normal case)

1. **Generate a strong key** and keep it safe (a password manager / secret store):
   ```
   openssl rand -base64 48
   ```
2. On the service (e.g. Railway), set **three** env vars for a **single** deploy:
   - `FIELD_ENC_KEY` = the new key from step 1
   - `REENCRYPT_FIELD_KEYS` = `true`
   - `REENCRYPT_FROM_KEY` = *(leave unset)* — it defaults to the old committed dev key, which is exactly what
     the data was written under when `FIELD_ENC_KEY` was missing. Only set this if you are rotating away from a
     *different* previously-real key.
3. **Deploy.** On boot: the guard passes (real key), then `FieldKeyReencryptor` runs once and re-encrypts every
   sensitive value from the previous key onto the new key. Watch the logs for:
   ```
   [FIELD REKEY] done: reencrypted=<N> alreadyCurrentKey=<M> undecryptable(left unchanged)=<F>
   ```
   `F` (undecryptable) should be **0**. If it is not, a value was written under some other key — do **not**
   proceed; set `REENCRYPT_FROM_KEY` to the correct previous key and redeploy.
4. **Spot-check**: open an employee record with a known PAN/Aadhaar and confirm the audited reveal returns the
   right value.
5. **Turn the migration off**: set `REENCRYPT_FIELD_KEYS` = `false` (or remove it) and redeploy. The runner is
   idempotent, so leaving it on is *safe* but pointlessly re-scans on every boot.

The migration is idempotent: values already under the new key are skipped, and anything it cannot decrypt with
either key is logged and **left unchanged** (never corrupted). There is a brief window (seconds, until the
runner finishes on the first deploy) during which reads of sensitive fields can error — run it at low traffic.

## When existing data is disposable (fresh / test-only DB)

Skip the re-encryption entirely: set a strong `FIELD_ENC_KEY`, leave `REENCRYPT_FIELD_KEYS` unset, deploy. Any
values written under the old key become unreadable and must be re-entered/re-seeded.

## Notes

- `JWT_SECRET` and `REFRESH_SECRET` are guarded the same way (guard fails prod boot on blank/dev-default), but
  they have **no stored ciphertext**, so rotating them needs no migration — it just invalidates live sessions.
- The `workingExperiences[].salaryCtc` value inside `form1_personal.data` (JSONB) is **not** field-encrypted in
  the current code, so it is out of scope for this migration.
