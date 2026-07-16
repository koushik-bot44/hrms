-- V20: Web Push subscriptions (§ Web Push, Stage N1). Additive.
--   A browser (via a Service Worker, added in N2) creates a PushSubscription — an endpoint URL plus the
--   p256dh + auth keys the server needs to encrypt payloads. We store ONE row per browser/device (the
--   endpoint is the natural key), owned by exactly one principal: a staff "User" OR a credentialed
--   "Employee" — the same nullable-userId/nullable-employeeId polymorphism used by message_recipients.
--   companyId is denormalized for tenant fan-out (nullable for platform roles like SUPER_ADMIN).
--   Timestamps are TIMESTAMP(3) UTC (the app-wide Prisma-style).

CREATE TABLE "push_subscriptions" (
    "id" TEXT NOT NULL,
    "userId" TEXT,              -- set iff a staff User owns this subscription
    "employeeId" TEXT,          -- set iff a credentialed Employee owns it
    "companyId" TEXT,           -- denormalized tenant; null for platform roles (SUPER_ADMIN/ACCOUNTS_ADMIN)
    "endpoint" TEXT NOT NULL,   -- the push service URL; one per browser/device
    "p256dh" TEXT NOT NULL,     -- the subscription's public key (base64url)
    "auth" TEXT NOT NULL,       -- the subscription's auth secret (base64url)
    "userAgent" TEXT,           -- best-effort label of the subscribing browser/device
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastUsedAt" TIMESTAMP(3),  -- stamped on a successful send
    CONSTRAINT "push_subscriptions_pkey" PRIMARY KEY ("id"),
    -- Exactly one principal owns a subscription (mirrors message_recipients).
    CONSTRAINT "push_subscriptions_one_principal" CHECK (
        ("userId" IS NOT NULL AND "employeeId" IS NULL)
        OR ("userId" IS NULL AND "employeeId" IS NOT NULL)
    )
);

-- A browser/device == one endpoint: subscribe is an UPSERT on this.
CREATE UNIQUE INDEX "push_subscriptions_endpoint_key" ON "push_subscriptions"("endpoint");
-- Fan-out lookups by owning principal (load a user's / employee's subscriptions to notify).
CREATE INDEX "push_subscriptions_userId_idx" ON "push_subscriptions"("userId");
CREATE INDEX "push_subscriptions_employeeId_idx" ON "push_subscriptions"("employeeId");

ALTER TABLE "push_subscriptions" ADD CONSTRAINT "push_subscriptions_userId_fkey"
    FOREIGN KEY ("userId") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "push_subscriptions" ADD CONSTRAINT "push_subscriptions_employeeId_fkey"
    FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "push_subscriptions" ADD CONSTRAINT "push_subscriptions_companyId_fkey"
    FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE CASCADE ON UPDATE CASCADE;
