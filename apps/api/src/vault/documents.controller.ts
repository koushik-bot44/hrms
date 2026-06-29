import { Body, Controller, Get, Ip, Param, Post } from '@nestjs/common';
import { ApiTags } from '@nestjs/swagger';
import {
  CreateCollectedSchema,
  CreateIssuedSchema,
  CreateReferencedSchema,
  TransitionSchema,
  type CreateCollectedDto,
  type CreateIssuedDto,
  type CreateReferencedDto,
  type DocumentResponse,
  type TransitionDto,
  type UploadTicket,
} from '@cdpp/shared';
import { DocumentsService } from './documents.service';
import { ZodBodyPipe } from '../common/pipes/zod-validation.pipe';

// Unguarded for now — the trust-tier guards + RBAC land in Phase 5 (SYSTEM/seeded operator).
@ApiTags('documents')
@Controller('documents')
export class DocumentsController {
  constructor(private readonly documents: DocumentsService) {}

  @Post('collected')
  createCollected(
    @Body(new ZodBodyPipe(CreateCollectedSchema)) dto: CreateCollectedDto,
  ): Promise<UploadTicket> {
    return this.documents.createCollected(dto);
  }

  @Post('issued')
  createIssued(
    @Body(new ZodBodyPipe(CreateIssuedSchema)) dto: CreateIssuedDto,
  ): Promise<DocumentResponse> {
    return this.documents.createIssued(dto);
  }

  @Post('referenced')
  createReferenced(
    @Body(new ZodBodyPipe(CreateReferencedSchema)) dto: CreateReferencedDto,
  ): Promise<DocumentResponse> {
    return this.documents.createReferenced(dto);
  }

  @Post(':id/confirm-upload')
  confirmUpload(@Param('id') id: string): Promise<DocumentResponse> {
    return this.documents.confirmUpload(id);
  }

  @Get(':id')
  get(@Param('id') id: string, @Ip() ip: string): Promise<DocumentResponse> {
    return this.documents.getDocument(id, ip);
  }

  @Post(':id/transition')
  transition(
    @Param('id') id: string,
    @Body(new ZodBodyPipe(TransitionSchema)) dto: TransitionDto,
  ): Promise<DocumentResponse> {
    return this.documents.transition(id, dto.toStatus, dto.successorId);
  }
}
