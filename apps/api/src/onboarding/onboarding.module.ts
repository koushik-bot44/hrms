import { Module } from '@nestjs/common';
import { StorageModule } from '../storage/storage.module';
import { OnboardingController } from './onboarding.controller';
import { OnboardingService } from './onboarding.service';

/** Employee onboarding self-service. Uses S3Service for document storage. */
@Module({
  imports: [StorageModule],
  controllers: [OnboardingController],
  providers: [OnboardingService],
})
export class OnboardingModule {}
