import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { PrismaClient } from '@prisma/client';
import { auditAppendOnlyExtension } from './audit-append-only.extension';

const buildGuardedClient = (base: PrismaClient) =>
  base.$extends(auditAppendOnlyExtension);

/** The append-only-guarded extended client type — every service queries through this. */
export type GuardedPrismaClient = ReturnType<typeof buildGuardedClient>;

@Injectable()
export class PrismaService extends PrismaClient implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(PrismaService.name);

  /**
   * Use this for ALL data access. It applies the append-only AuditLog guard and shares
   * the base client's connection.
   */
  readonly guarded: GuardedPrismaClient;

  constructor() {
    super();
    this.guarded = buildGuardedClient(this);
  }

  async onModuleInit(): Promise<void> {
    await this.$connect();
    this.logger.log('Prisma connected to the database');
  }

  async onModuleDestroy(): Promise<void> {
    await this.$disconnect();
  }
}
