import { Body, Controller, Get, Ip, Param, Post, Put } from '@nestjs/common';
import { ApiBearerAuth, ApiTags } from '@nestjs/swagger';
import {
  DocumentUploadRequestSchema,
  SaveSectionSchema,
  type DocumentDto,
  type DocumentUploadRequestInput,
  type OnboardingDashboard,
  type PresignedUpload,
  type PresignedView,
  type ProfileSectionDto,
  type SaveSectionInput,
} from '@ihrms/shared';
import { ZodValidationPipe } from '../common/pipes/zod-validation.pipe';
import { EmployeeOnly } from '../auth/decorators/scope.decorator';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import type { EmployeePrincipal } from '../auth/principal';
import { OnboardingService } from './onboarding.service';

/** Employee self-service onboarding (§3.2). EMPLOYEE-only; everything is own-record. */
@ApiTags('onboarding')
@ApiBearerAuth()
@EmployeeOnly()
@Controller('me/onboarding')
export class OnboardingController {
  constructor(private readonly onboarding: OnboardingService) {}

  @Get()
  dashboard(@CurrentUser() emp: EmployeePrincipal): Promise<OnboardingDashboard> {
    return this.onboarding.dashboard(emp);
  }

  @Put('sections/:key')
  saveSection(
    @Param('key') key: string,
    @Body(new ZodValidationPipe(SaveSectionSchema)) body: SaveSectionInput,
    @CurrentUser() emp: EmployeePrincipal,
    @Ip() ip: string,
  ): Promise<ProfileSectionDto> {
    return this.onboarding.saveSection(emp, key, body, ip);
  }

  @Post('documents')
  requestUpload(
    @Body(new ZodValidationPipe(DocumentUploadRequestSchema)) body: DocumentUploadRequestInput,
    @CurrentUser() emp: EmployeePrincipal,
    @Ip() ip: string,
  ): Promise<PresignedUpload> {
    return this.onboarding.requestUpload(emp, body, ip);
  }

  @Post('documents/:id/confirm')
  confirm(
    @Param('id') id: string,
    @CurrentUser() emp: EmployeePrincipal,
    @Ip() ip: string,
  ): Promise<DocumentDto> {
    return this.onboarding.confirmUpload(emp, id, ip);
  }

  @Get('documents/:id/url')
  viewUrl(
    @Param('id') id: string,
    @CurrentUser() emp: EmployeePrincipal,
    @Ip() ip: string,
  ): Promise<PresignedView> {
    return this.onboarding.documentViewUrl(emp, id, ip);
  }

  @Post('submit')
  submit(@CurrentUser() emp: EmployeePrincipal, @Ip() ip: string): Promise<OnboardingDashboard> {
    return this.onboarding.submit(emp, ip);
  }
}
