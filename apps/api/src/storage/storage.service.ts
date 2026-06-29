import { randomUUID } from 'node:crypto';
import {
  Injectable,
  Logger,
  type OnModuleInit,
  ServiceUnavailableException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import {
  CreateBucketCommand,
  GetObjectCommand,
  HeadBucketCommand,
  PutObjectCommand,
  S3Client,
} from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import type { Env } from '../config/env.validation';

/**
 * S3-compatible object storage (AWS SDK v3) configured from S3_* env — point at local
 * MinIO now, swap to AWS/R2 later by changing endpoint + keys. Files are reached only via
 * short-lived presigned URLs; raw keys/URLs are never returned to clients (§6).
 */
@Injectable()
export class S3Service implements OnModuleInit {
  private readonly logger = new Logger(S3Service.name);
  private readonly client: S3Client | null;
  private readonly bucket: string;

  constructor(config: ConfigService<Env, true>) {
    this.bucket = config.get('S3_BUCKET', { infer: true });
    const accessKeyId = config.get('S3_ACCESS_KEY_ID', { infer: true });
    const secretAccessKey = config.get('S3_SECRET_ACCESS_KEY', { infer: true });
    const endpoint = config.get('S3_ENDPOINT', { infer: true });

    if (!this.bucket || !accessKeyId || !secretAccessKey) {
      // Not configured yet (e.g. before MinIO/AWS is wired) — the app still boots; only
      // the upload endpoints fail (with 503) until S3_* is set.
      this.client = null;
      return;
    }

    this.client = new S3Client({
      region: config.get('S3_REGION', { infer: true }),
      endpoint: endpoint || undefined,
      forcePathStyle: config.get('S3_FORCE_PATH_STYLE', { infer: true }),
      credentials: { accessKeyId, secretAccessKey },
    });
  }

  isConfigured(): boolean {
    return this.client !== null;
  }

  async onModuleInit(): Promise<void> {
    if (!this.client) {
      this.logger.warn('S3 storage not configured (S3_* unset) — document uploads are disabled');
      return;
    }
    await this.ensureBucket();
  }

  private async ensureBucket(): Promise<void> {
    try {
      await this.client!.send(new HeadBucketCommand({ Bucket: this.bucket }));
      this.logger.log(`S3 bucket "${this.bucket}" ready`);
    } catch {
      try {
        await this.client!.send(new CreateBucketCommand({ Bucket: this.bucket }));
        this.logger.log(`S3 bucket "${this.bucket}" created`);
      } catch (err) {
        this.logger.error(
          `Could not ensure bucket "${this.bucket}": ${err instanceof Error ? err.message : String(err)}`,
        );
      }
    }
  }

  /** A unique object key namespaced under the employee's record. */
  buildKey(companyId: string, employeeId: string, sectionKey: string, fileName: string): string {
    const safe = fileName.replace(/[^a-zA-Z0-9._-]/g, '_').slice(0, 120);
    return `companies/${companyId}/employees/${employeeId}/${sectionKey}/${randomUUID()}-${safe}`;
  }

  presignedPutUrl(key: string, contentType: string, expiresInSeconds: number): Promise<string> {
    return getSignedUrl(
      this.requireClient(),
      new PutObjectCommand({ Bucket: this.bucket, Key: key, ContentType: contentType }),
      { expiresIn: expiresInSeconds },
    );
  }

  presignedGetUrl(key: string, expiresInSeconds: number): Promise<string> {
    return getSignedUrl(
      this.requireClient(),
      new GetObjectCommand({ Bucket: this.bucket, Key: key }),
      { expiresIn: expiresInSeconds },
    );
  }

  /** Server-side read of the stored bytes (for hashing). */
  async getObjectBytes(key: string): Promise<Buffer> {
    const result = await this.requireClient().send(
      new GetObjectCommand({ Bucket: this.bucket, Key: key }),
    );
    if (!result.Body) {
      throw new ServiceUnavailableException('Stored object is empty');
    }
    const bytes = await (
      result.Body as unknown as { transformToByteArray(): Promise<Uint8Array> }
    ).transformToByteArray();
    return Buffer.from(bytes);
  }

  private requireClient(): S3Client {
    if (!this.client) {
      throw new ServiceUnavailableException('File storage is not configured');
    }
    return this.client;
  }
}
