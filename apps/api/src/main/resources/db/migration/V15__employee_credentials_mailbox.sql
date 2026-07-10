-- V15: employee internal credentials + mailbox (§8, Stage 5). Additive.
--   * Employees gain a mailbox address + password (assigned by HR after Manager approval); the address
--     IS a login email. All four columns are nullable — they exist only once credentials are assigned.
--   * The mail tables gain a nullable EMPLOYEE-id column beside the user-id one, so a sender / recipient /
--     uploader can be a User OR an Employee (exactly one of the two id columns is set per row). Ids are
--     globally-unique cuids, so callers query "my mail" by matching either column against the same id.

-- --- Employee credentials -----------------------------------------------------
ALTER TABLE "employees" ADD COLUMN "mailLocalPart" TEXT;
ALTER TABLE "employees" ADD COLUMN "mailAddress" TEXT;
ALTER TABLE "employees" ADD COLUMN "passwordHash" TEXT;
ALTER TABLE "employees" ADD COLUMN "credentialsAssignedAt" TIMESTAMP(3);
-- Unique across employees; multiple NULLs are allowed (uncredentialed employees don't collide). Cross-table
-- uniqueness (vs users.email / employees.email) is enforced by the AccountEmails guard at assignment.
CREATE UNIQUE INDEX "employees_mailAddress_key" ON "employees"("mailAddress");

-- --- messages: the sender may be an employee ----------------------------------
ALTER TABLE "messages" ADD COLUMN "senderEmployeeId" TEXT;
ALTER TABLE "messages" ALTER COLUMN "senderUserId" DROP NOT NULL;
CREATE INDEX "messages_senderEmployeeId_idx" ON "messages"("senderEmployeeId");
ALTER TABLE "messages" ADD CONSTRAINT "messages_senderEmployeeId_fkey"
    FOREIGN KEY ("senderEmployeeId") REFERENCES "employees"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- --- message_recipients: the recipient may be an employee ---------------------
ALTER TABLE "message_recipients" ADD COLUMN "recipientEmployeeId" TEXT;
ALTER TABLE "message_recipients" ALTER COLUMN "recipientUserId" DROP NOT NULL;
CREATE INDEX "message_recipients_recipientEmployeeId_idx"
    ON "message_recipients"("recipientEmployeeId", "readAt");
ALTER TABLE "message_recipients" ADD CONSTRAINT "message_recipients_recipientEmployeeId_fkey"
    FOREIGN KEY ("recipientEmployeeId") REFERENCES "employees"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- --- message_attachments: the uploader may be an employee ---------------------
ALTER TABLE "message_attachments" ADD COLUMN "uploaderEmployeeId" TEXT;
ALTER TABLE "message_attachments" ALTER COLUMN "uploaderUserId" DROP NOT NULL;
ALTER TABLE "message_attachments" ADD CONSTRAINT "message_attachments_uploaderEmployeeId_fkey"
    FOREIGN KEY ("uploaderEmployeeId") REFERENCES "employees"("id") ON DELETE CASCADE ON UPDATE CASCADE;
