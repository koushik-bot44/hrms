/**
 * Seed: a single SUPER_ADMIN User with dev credentials. No company data.
 * Idempotent via upsert, safe to re-run.
 */
import { PrismaClient, UserRole } from '@prisma/client';
import { hash } from '@node-rs/argon2';

const prisma = new PrismaClient();

const DEV_EMAIL = 'superadmin@ihrms.local';
const DEV_PASSWORD = 'SuperAdmin@123'; // dev-only

async function main() {
  // Staff credentials are argon2-hashed (§6).
  const passwordHash = await hash(DEV_PASSWORD);
  const admin = await prisma.user.upsert({
    where: { email: DEV_EMAIL },
    update: { passwordHash },
    create: {
      email: DEV_EMAIL,
      name: 'Super Admin',
      role: UserRole.SUPER_ADMIN,
      passwordHash,
      status: 'ACTIVE',
    },
  });
  console.log(`Seed complete: SUPER_ADMIN ${admin.email} (dev password: ${DEV_PASSWORD})`);
}

main()
  .then(async () => {
    await prisma.$disconnect();
  })
  .catch(async (err) => {
    console.error(err);
    await prisma.$disconnect();
    process.exit(1);
  });
