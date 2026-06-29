import { Module } from '@nestjs/common';
import { DocumentsService } from './documents.service';
import { DocumentsController } from './documents.controller';
import { IdentityModule } from '../identity/identity.module';

// StorageModule, AuditModule, PrismaModule are @Global.
@Module({
  imports: [IdentityModule],
  controllers: [DocumentsController],
  providers: [DocumentsService],
  exports: [DocumentsService],
})
export class VaultModule {}
