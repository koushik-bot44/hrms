import { Body, Controller, Get, Ip, Post } from '@nestjs/common';
import { ApiBearerAuth, ApiTags } from '@nestjs/swagger';
import {
  OnboardEmployeeSchema,
  UserRole,
  type EmployeeSummary,
  type OnboardEmployeeInput,
  type OnboardEmployeeResult,
} from '@ihrms/shared';
import { ZodValidationPipe } from '../common/pipes/zod-validation.pipe';
import { Roles } from '../auth/decorators/scope.decorator';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import type { UserPrincipal } from '../auth/principal';
import { EmployeesService } from './employees.service';

/** Employee onboarding (§3.2/§5). HR-only; scoped to the HR's company. */
@ApiTags('employees')
@ApiBearerAuth()
@Roles(UserRole.HR)
@Controller('employees')
export class EmployeesController {
  constructor(private readonly employees: EmployeesService) {}

  @Post()
  onboard(
    @Body(new ZodValidationPipe(OnboardEmployeeSchema)) body: OnboardEmployeeInput,
    @CurrentUser() actor: UserPrincipal,
    @Ip() ip: string,
  ): Promise<OnboardEmployeeResult> {
    return this.employees.onboard(body, actor, ip);
  }

  @Get()
  list(@CurrentUser() actor: UserPrincipal): Promise<EmployeeSummary[]> {
    return this.employees.listMine(actor);
  }
}
