import { Controller, Get } from '@nestjs/common';
import { ApiOkResponse, ApiTags } from '@nestjs/swagger';
import type { HealthResponse } from '@ihrms/shared';
import { PrismaService } from '../prisma/prisma.service';

@ApiTags('health')
@Controller('health')
export class HealthController {
  constructor(private readonly prisma: PrismaService) {}

  @Get()
  @ApiOkResponse({
    description: 'Liveness + database reachability',
    schema: {
      type: 'object',
      properties: {
        status: { type: 'string', example: 'ok' },
        db: { type: 'string', enum: ['up', 'down'], example: 'up' },
      },
    },
  })
  async check(): Promise<HealthResponse> {
    let db: HealthResponse['db'] = 'down';
    try {
      await this.prisma.$queryRaw`SELECT 1`;
      db = 'up';
    } catch {
      db = 'down';
    }
    return { status: 'ok', db };
  }
}
