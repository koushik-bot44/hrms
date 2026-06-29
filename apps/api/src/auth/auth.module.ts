import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { PassportModule } from '@nestjs/passport';
import { AccessControlService } from './access-control.service';
import { AuthController } from './auth.controller';
import { AuthService } from './auth.service';
import { JwtStrategy } from './jwt.strategy';
import { MailService } from './mail.service';

/**
 * Authentication + the centralized access-control service (§6). Secrets are passed
 * per sign/verify call, so JwtModule needs no global secret. AccessControlService is
 * exported for feature modules to enforce resource scope.
 */
@Module({
  imports: [PassportModule, JwtModule.register({})],
  controllers: [AuthController],
  providers: [AuthService, AccessControlService, MailService, JwtStrategy],
  exports: [AccessControlService],
})
export class AuthModule {}
