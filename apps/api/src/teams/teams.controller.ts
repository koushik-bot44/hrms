import {
  Body,
  Controller,
  Delete,
  Get,
  Ip,
  Param,
  Patch,
  Post,
  Put,
  Query,
} from '@nestjs/common';
import { ApiBearerAuth, ApiTags } from '@nestjs/swagger';
import {
  AssignMemberSchema,
  CreateTeamSchema,
  UpdateTeamSchema,
  UserRole,
  type AssignMemberInput,
  type AssignMemberResult,
  type AssignableUser,
  type CreateTeamInput,
  type TeamDetail,
  type TeamRole,
  type TeamSummary,
  type UpdateTeamInput,
} from '@ihrms/shared';
import { ZodValidationPipe } from '../common/pipes/zod-validation.pipe';
import { Roles } from '../auth/decorators/scope.decorator';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import type { UserPrincipal } from '../auth/principal';
import { TeamsService } from './teams.service';

/** Team management (§2/§3.1). COMPANY_ADMIN-only; scoped to the admin's company. */
@ApiTags('teams')
@ApiBearerAuth()
@Roles(UserRole.COMPANY_ADMIN)
@Controller('teams')
export class TeamsController {
  constructor(private readonly teams: TeamsService) {}

  @Post()
  create(
    @Body(new ZodValidationPipe(CreateTeamSchema)) body: CreateTeamInput,
    @CurrentUser() actor: UserPrincipal,
    @Ip() ip: string,
  ): Promise<TeamDetail> {
    return this.teams.create(body, actor, ip);
  }

  @Get()
  list(@CurrentUser() actor: UserPrincipal): Promise<TeamSummary[]> {
    return this.teams.list(actor);
  }

  // Declared before ':id' so it is not captured by the dynamic segment.
  @Get('assignable-users')
  assignable(
    @Query('role') role: string,
    @CurrentUser() actor: UserPrincipal,
  ): Promise<AssignableUser[]> {
    return this.teams.assignableUsers(actor, role);
  }

  @Get(':id')
  get(@Param('id') id: string, @CurrentUser() actor: UserPrincipal): Promise<TeamDetail> {
    return this.teams.getDetail(id, actor);
  }

  @Patch(':id')
  update(
    @Param('id') id: string,
    @Body(new ZodValidationPipe(UpdateTeamSchema)) body: UpdateTeamInput,
    @CurrentUser() actor: UserPrincipal,
    @Ip() ip: string,
  ): Promise<TeamDetail> {
    return this.teams.update(id, body, actor, ip);
  }

  @Delete(':id')
  async remove(
    @Param('id') id: string,
    @CurrentUser() actor: UserPrincipal,
    @Ip() ip: string,
  ): Promise<{ ok: true }> {
    await this.teams.remove(id, actor, ip);
    return { ok: true };
  }

  @Put(':id/hr')
  assignHr(
    @Param('id') id: string,
    @Body(new ZodValidationPipe(AssignMemberSchema)) body: AssignMemberInput,
    @CurrentUser() actor: UserPrincipal,
    @Ip() ip: string,
  ): Promise<AssignMemberResult> {
    return this.teams.assign(id, UserRole.HR as TeamRole, body, actor, ip);
  }

  @Put(':id/manager')
  assignManager(
    @Param('id') id: string,
    @Body(new ZodValidationPipe(AssignMemberSchema)) body: AssignMemberInput,
    @CurrentUser() actor: UserPrincipal,
    @Ip() ip: string,
  ): Promise<AssignMemberResult> {
    return this.teams.assign(id, UserRole.MANAGER as TeamRole, body, actor, ip);
  }
}
