import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { TeamsController } from './teams.controller';
import { TeamsService } from './teams.service';

/**
 * Team management. Imports AuthModule for MailService (staff invites); PrismaService and
 * AuditService are global.
 */
@Module({
  imports: [AuthModule],
  controllers: [TeamsController],
  providers: [TeamsService],
})
export class TeamsModule {}
