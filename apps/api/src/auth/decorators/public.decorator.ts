import { SetMetadata } from '@nestjs/common';
import { IS_PUBLIC_KEY } from '../auth.constants';

/** Marks a route as not requiring authentication (e.g. /health, /auth/login). */
export const Public = () => SetMetadata(IS_PUBLIC_KEY, true);
