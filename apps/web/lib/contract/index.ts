/**
 * The IHRMS cross-app contract surface. Enums (§4) + employee-ID helpers (§5) + request/form
 * zod schemas live locally; RESPONSE DTO types are derived from the Spring Boot OpenAPI schema
 * (`./responses.ts`, generated via `pnpm gen:types`). Components import everything from here.
 */
export * from './enums';
export * from './ids';
export * from './dto';
// Response DTO types derived from the Java OpenAPI schema (the single source of truth).
export * from './responses';
