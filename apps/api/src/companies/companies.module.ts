import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { CompaniesController } from './companies.controller';
import { CompaniesService } from './companies.service';

/**
 * Company management. Imports AuthModule for MailService (the company-admin invite);
 * PrismaService and AuditService are global.
 */
@Module({
  imports: [AuthModule],
  controllers: [CompaniesController],
  providers: [CompaniesService],
})
export class CompaniesModule {}
