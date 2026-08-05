-- V35: THE OFFER LETTER opens onboarding (ARCHITECTURE.md §3.2). At invite time HR provides the offer terms
-- (salary, location; joining date + designation + name are reused from the Form-2 onboard). The invited
-- employee's onboarding portal shows the offer FIRST — read (scroll-to-consent), sign, ACCEPT — and Forms
-- 1/2/3/4 stay LOCKED (server-side) until accepted; the accepted PDF is stored on the record. Additive only.
--
-- A sibling table (like offboarding_letters): the row only exists for NEW onboards, so existing in-flight
-- employees have NO row and are never gated. It does not touch any form/section table, so no onboarding gate
-- is jammed. status SENT -> ACCEPTED; terms is a JSONB snapshot {joiningDate, salary, location, offerDate}.

CREATE TYPE "OfferStatus" AS ENUM ('SENT', 'ACCEPTED');

CREATE TABLE "employee_offers" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    -- Snapshot of the terms HR entered at invite (rendered into the PDF only; never written into Form data).
    "terms" JSONB NOT NULL,
    "status" "OfferStatus" NOT NULL DEFAULT 'SENT',
    "acceptedAt" timestamp(3),
    -- Stable S3 key of the accepted PDF (companies/{cid}/employees/{eid}/offer/OFFER_LETTER.pdf); null until accepted.
    "storageKey" TEXT,
    "createdAt" timestamp(3) NOT NULL DEFAULT now(),
    CONSTRAINT "employee_offers_pkey" PRIMARY KEY ("id")
);

-- One offer per employee.
CREATE UNIQUE INDEX "employee_offers_employeeId_key" ON "employee_offers"("employeeId");

ALTER TABLE "employee_offers" ADD CONSTRAINT "employee_offers_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
