import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { APP_GUARD, APP_INTERCEPTOR } from '@nestjs/core';
import { validateEnv } from './config/env.validation';
import { PrismaModule } from './prisma/prisma.module';
import { HealthModule } from './health/health.module';
import { AuthModule } from './auth/auth.module';
import { AuditModule } from './audit/audit.module';
import { CompaniesModule } from './companies/companies.module';
import { JwtAuthGuard } from './auth/guards/jwt-auth.guard';
import { ScopeGuard } from './auth/guards/scope.guard';
import { AuditInterceptor } from './audit/audit.interceptor';

@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
      cache: true,
      validate: validateEnv,
    }),
    PrismaModule,
    AuditModule,
    AuthModule,
    CompaniesModule,
    HealthModule,
  ],
  providers: [
    // Order matters: authenticate (JwtAuthGuard) before scope-gating (ScopeGuard).
    { provide: APP_GUARD, useClass: JwtAuthGuard },
    { provide: APP_GUARD, useClass: ScopeGuard },
    // Audit every successful mutation (§7).
    { provide: APP_INTERCEPTOR, useClass: AuditInterceptor },
  ],
})
export class AppModule {}
