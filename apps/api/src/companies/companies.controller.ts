import { Body, Controller, Get, Ip, Param, Patch, Post } from '@nestjs/common';
import { ApiBearerAuth, ApiTags } from '@nestjs/swagger';
import {
  CreateCompanySchema,
  ProvisionCompanyAdminSchema,
  UpdateCompanySchema,
  UserRole,
  type CompanyDetail,
  type CompanySummary,
  type CreateCompanyInput,
  type ProvisionCompanyAdminInput,
  type ProvisionCompanyAdminResult,
  type UpdateCompanyInput,
} from '@ihrms/shared';
import { ZodValidationPipe } from '../common/pipes/zod-validation.pipe';
import { Roles } from '../auth/decorators/scope.decorator';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import type { UserPrincipal } from '../auth/principal';
import { CompaniesService } from './companies.service';

/** Company management (§2/§3.1). SUPER_ADMIN-only — gated by the class-level @Roles. */
@ApiTags('companies')
@ApiBearerAuth()
@Roles(UserRole.SUPER_ADMIN)
@Controller('companies')
export class CompaniesController {
  constructor(private readonly companies: CompaniesService) {}

  @Post()
  create(
    @Body(new ZodValidationPipe(CreateCompanySchema)) body: CreateCompanyInput,
    @CurrentUser() actor: UserPrincipal,
    @Ip() ip: string,
  ): Promise<CompanyDetail> {
    return this.companies.create(body, actor, ip);
  }

  @Get()
  list(): Promise<CompanySummary[]> {
    return this.companies.list();
  }

  @Get(':id')
  get(@Param('id') id: string): Promise<CompanyDetail> {
    return this.companies.getDetail(id);
  }

  @Patch(':id')
  update(
    @Param('id') id: string,
    @Body(new ZodValidationPipe(UpdateCompanySchema)) body: UpdateCompanyInput,
    @CurrentUser() actor: UserPrincipal,
    @Ip() ip: string,
  ): Promise<CompanyDetail> {
    return this.companies.update(id, body, actor, ip);
  }

  @Post(':id/admin')
  provisionAdmin(
    @Param('id') id: string,
    @Body(new ZodValidationPipe(ProvisionCompanyAdminSchema)) body: ProvisionCompanyAdminInput,
    @CurrentUser() actor: UserPrincipal,
    @Ip() ip: string,
  ): Promise<ProvisionCompanyAdminResult> {
    return this.companies.provisionAdmin(id, body, actor, ip);
  }
}
