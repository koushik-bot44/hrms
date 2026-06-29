import { Global, Module } from '@nestjs/common';
import { AuditService } from './audit.service';

/** Provides AuditService app-wide (the global AuditInterceptor and services depend on it). */
@Global()
@Module({
  providers: [AuditService],
  exports: [AuditService],
})
export class AuditModule {}
