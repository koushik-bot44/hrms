-- AuditLog is append-only (ARCHITECTURE.md §7). Enforce it at the database: any UPDATE or
-- DELETE on a row raises an exception (mirrors the archived Prisma client extension that
-- forbade update/delete/upsert). INSERT is unaffected; TRUNCATE (table-level admin op) is
-- not a row trigger and is intentionally left to operators.

CREATE OR REPLACE FUNCTION audit_logs_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'audit_logs is append-only: % is not allowed', TG_OP
    USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_logs_no_mutate
  BEFORE UPDATE OR DELETE ON "audit_logs"
  FOR EACH ROW EXECUTE FUNCTION audit_logs_append_only();
