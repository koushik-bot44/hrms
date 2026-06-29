import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { EmployeesController } from './employees.controller';
import { EmployeesService } from './employees.service';

/**
 * Employee onboarding. Imports AuthModule for MailService (the onboarding email);
 * PrismaService and AuditService are global.
 */
@Module({
  imports: [AuthModule],
  controllers: [EmployeesController],
  providers: [EmployeesService],
})
export class EmployeesModule {}
