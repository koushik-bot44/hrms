import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import {
  S3Client,
  PutObjectCommand,
  GetObjectCommand,
  CopyObjectCommand,
  HeadBucketCommand,
  CreateBucketCommand,
} from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import type { Env } from '../config/env.validation';

/** Short-lived presigned URL lifetime (seconds). */
export const PRESIGNED_TTL_SECONDS = 300; // ~5 minutes

/**
 * One S3-compatible storage seam (MinIO/local, AWS/R2 in prod). Clients only ever
 * receive opaque presigned URLs — raw keys/bucket/endpoint never leave this service.
 */
@Injectable()
export class StorageService implements OnModuleInit {
  private readonly logger = new Logger(StorageService.name);
  private readonly s3: S3Client;
  private readonly bucket: string;
  private readonly configured: boolean;

  constructor(private readonly config: ConfigService<Env, true>) {
    const endpoint = this.config.get('S3_ENDPOINT', { infer: true });
    const accessKeyId = this.config.get('S3_ACCESS_KEY_ID', { infer: true });
    const secretAccessKey = this.config.get('S3_SECRET_ACCESS_KEY', { infer: true });
    this.bucket = this.config.get('S3_BUCKET', { infer: true });
    this.configured = Boolean(this.bucket && accessKeyId && secretAccessKey);

    this.s3 = new S3Client({
      region: this.config.get('S3_REGION', { infer: true }) || 'us-east-1',
      endpoint: endpoint || undefined,
      forcePathStyle: this.config.get('S3_FORCE_PATH_STYLE', { infer: true }),
      credentials: { accessKeyId, secretAccessKey },
    });
  }

  async onModuleInit(): Promise<void> {
    // Idempotent bucket creation on boot, only when storage is configured (so the
    // app still boots in environments without S3, e.g. unit tests that don't touch it).
    if (this.configured) {
      await this.ensureBucket();
    } else {
      this.logger.warn('S3 not fully configured — storage operations will fail until set');
    }
  }

  /** Create the bucket if it does not already exist. Idempotent. */
  async ensureBucket(): Promise<void> {
    try {
      await this.s3.send(new HeadBucketCommand({ Bucket: this.bucket }));
    } catch {
      await this.s3.send(new CreateBucketCommand({ Bucket: this.bucket }));
      this.logger.log(`Created bucket "${this.bucket}"`);
    }
  }

  /** Short-lived presigned PUT for a client-side upload. */
  getUploadUrl(key: string, expiresIn: number = PRESIGNED_TTL_SECONDS): Promise<string> {
    return getSignedUrl(this.s3, new PutObjectCommand({ Bucket: this.bucket, Key: key }), {
      expiresIn,
    });
  }

  /** Short-lived presigned GET for a client-side download. */
  getDownloadUrl(key: string, expiresIn: number = PRESIGNED_TTL_SECONDS): Promise<string> {
    return getSignedUrl(this.s3, new GetObjectCommand({ Bucket: this.bucket, Key: key }), {
      expiresIn,
    });
  }

  /** Server-side fetch of the object bytes (for SHA-256 hashing). */
  async getObjectBytes(key: string): Promise<Buffer> {
    const res = await this.s3.send(new GetObjectCommand({ Bucket: this.bucket, Key: key }));
    if (!res.Body) {
      throw new Error(`Object "${key}" has no body`);
    }
    const bytes = await res.Body.transformToByteArray();
    return Buffer.from(bytes);
  }

  /** Server-side copy (used to snapshot each version under its own key). */
  async copyObject(sourceKey: string, destKey: string): Promise<void> {
    await this.s3.send(
      new CopyObjectCommand({
        Bucket: this.bucket,
        CopySource: `${this.bucket}/${sourceKey}`,
        Key: destKey,
      }),
    );
  }
}
