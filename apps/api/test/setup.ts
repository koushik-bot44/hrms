import 'reflect-metadata';

// Runs before any spec module is imported, so ConfigModule.forRoot() (which validates
// env at import time) has what it needs. Integration specs gate on DPP_TEST_DB_URL and
// it is mapped to DATABASE_URL here. The S3 defaults match the s3rver started in-spec.
process.env.NODE_ENV ||= 'test';
process.env.DATABASE_URL ||=
  process.env.DPP_TEST_DB_URL ||
  'postgresql://placeholder:placeholder@localhost:5432/placeholder';
process.env.S3_ENDPOINT ||= 'http://127.0.0.1:54330';
process.env.S3_REGION ||= 'us-east-1';
process.env.S3_BUCKET ||= 'cdpp-test';
process.env.S3_ACCESS_KEY_ID ||= 'S3RVER';
process.env.S3_SECRET_ACCESS_KEY ||= 'S3RVER';
process.env.S3_FORCE_PATH_STYLE ||= 'true';
