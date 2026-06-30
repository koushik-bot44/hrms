-- DB-backed document storage (STORAGE_DRIVER=db). When object storage (S3/MinIO/R2) is not
-- configured, uploaded document bytes are stored here instead, so no external bucket is needed.
-- The primary key is the document's opaque storageKey (never exposed to clients — the public
-- /storage/blobs endpoint addresses a blob only via an encrypted, time-limited token). Additive:
-- the S3 path ignores this table entirely.
CREATE TABLE "document_blobs" (
    "storageKey" TEXT NOT NULL,
    "data" BYTEA NOT NULL,
    "contentType" TEXT,
    "sizeBytes" INTEGER NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "document_blobs_pkey" PRIMARY KEY ("storageKey")
);
