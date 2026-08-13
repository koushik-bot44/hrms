-- V40: letterhead as a DOCUMENT + a Word-like margin box (ARCHITECTURE.md §3.5, rewritten). Replaces the
-- V39 header/footer BAND model: the SUPER_ADMIN now uploads ONE letterhead file (PDF, or Word converted to
-- PDF) whose FIRST PAGE becomes the page background of every generated pipeline document, and sets the margin
-- box (top/bottom/left/right, in points) where content may sit — like adjusting margins in Word.
--
-- Additive + nullable; NOTHING is dropped. The V39 band columns (letterheadHeader*/letterheadFooter*) are kept
-- in place, DEPRECATED and unused by the new model (additive discipline). There is no data to migrate: the band
-- feature was newly introduced and the two models are structurally different (two margin strips vs a full-page
-- template), so a company with old band data simply shows "no letterhead" until it re-uploads a full file.
--
-- letterheadPdfKey IS NULL  => no letterhead => documents render plain (unchanged). Page + margin geometry are
-- stored in points (1/72in, the PDF-native unit) so the renderer aligns the content box to the artwork exactly.
ALTER TABLE "companies" ADD COLUMN "letterheadPdfKey" TEXT;
ALTER TABLE "companies" ADD COLUMN "letterheadOriginalKey" TEXT;
ALTER TABLE "companies" ADD COLUMN "letterheadOriginalType" TEXT;
ALTER TABLE "companies" ADD COLUMN "letterheadPreviewKey" TEXT;
ALTER TABLE "companies" ADD COLUMN "letterheadPageWidthPt" DOUBLE PRECISION;
ALTER TABLE "companies" ADD COLUMN "letterheadPageHeightPt" DOUBLE PRECISION;
ALTER TABLE "companies" ADD COLUMN "letterheadMarginTopPt" DOUBLE PRECISION;
ALTER TABLE "companies" ADD COLUMN "letterheadMarginBottomPt" DOUBLE PRECISION;
ALTER TABLE "companies" ADD COLUMN "letterheadMarginLeftPt" DOUBLE PRECISION;
ALTER TABLE "companies" ADD COLUMN "letterheadMarginRightPt" DOUBLE PRECISION;
