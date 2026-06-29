import { Module } from '@nestjs/common';
import { S3Service } from './storage.service';

/** S3-compatible object storage, configured from S3_* env. */
@Module({
  providers: [S3Service],
  exports: [S3Service],
})
export class StorageModule {}
