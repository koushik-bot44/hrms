-- V29: workflow inversion — approval authority moves MANAGER -> HR (ARCHITECTURE.md §3.3/§5/§6). HR now
-- verifies and then APPROVES (onto a team) or REJECTS directly; the route-to-manager step is retired and the
-- Manager's area becomes a read-only team-onboarding history. Additive/relaxing only — nothing is dropped and
-- the EmployeeStatus/ApprovalStatus enum values (incl. HR_VERIFIED) are retained. HR_VERIFIED is REPURPOSED as
-- "verified, awaiting HR's approve/reject decision", so employees already in it need no status change.

-- A team may have no manager, so an HR-approved decision record can carry a null managerUserId (the row is
-- still the "approved onto team X" record; a manager-less team simply gets no notification).
ALTER TABLE "approval_requests" ALTER COLUMN "managerUserId" DROP NOT NULL;

-- Retire in-flight routing: the manager approval inbox is gone, so PENDING requests can never be decided by a
-- manager again. There is no CANCELLED status, so delete them — the employees stay HR_VERIFIED and are now
-- decidable by HR. DECIDED rows (APPROVED/REJECTED) are the historical manager-era record and are left intact.
DELETE FROM "approval_requests" WHERE "status" = 'PENDING';
