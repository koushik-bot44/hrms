-- V39: per-company letterhead (ARCHITECTURE.md §3.5). SUPER_ADMIN uploads a HEADER and/or FOOTER band image
-- (one per company per part, replaceable); every pipeline document generated FROM THAT POINT ON renders on it
-- (Forms 1-4 excluded). Additive + nullable; nothing is dropped. A part is "unset" when its key IS NULL, in
-- which case that band renders plain (unchanged). Pixel dimensions are stored so the renderer can derive the
-- @page band heights from the image aspect ratio without re-reading the object.
ALTER TABLE "companies" ADD COLUMN "letterheadHeaderKey" TEXT;
ALTER TABLE "companies" ADD COLUMN "letterheadHeaderType" TEXT;
ALTER TABLE "companies" ADD COLUMN "letterheadHeaderWidth" INTEGER;
ALTER TABLE "companies" ADD COLUMN "letterheadHeaderHeight" INTEGER;
ALTER TABLE "companies" ADD COLUMN "letterheadFooterKey" TEXT;
ALTER TABLE "companies" ADD COLUMN "letterheadFooterType" TEXT;
ALTER TABLE "companies" ADD COLUMN "letterheadFooterWidth" INTEGER;
ALTER TABLE "companies" ADD COLUMN "letterheadFooterHeight" INTEGER;
ALTER TABLE "companies" ADD COLUMN "letterheadUpdatedAt" TIMESTAMP(3);
ALTER TABLE "companies" ADD COLUMN "letterheadUpdatedByUserId" TEXT;

ALTER TABLE "companies"
  ADD CONSTRAINT "companies_letterheadUpdatedByUserId_fkey"
  FOREIGN KEY ("letterheadUpdatedByUserId") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;
