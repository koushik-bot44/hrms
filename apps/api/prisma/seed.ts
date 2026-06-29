/**
 * Seed: one Entity ("NAME") + one HR Operator.
 * Idempotent via upsert, so it is safe to re-run.
 */
import { PrismaClient, Role, OnboardingState } from '@prisma/client';

const prisma = new PrismaClient();

async function main() {
  const entity = await prisma.entity.upsert({
    where: { code: 'NAME' },
    update: {},
    create: { code: 'NAME', name: 'NAME' },
  });

  const operator = await prisma.user.upsert({
    where: { email: 'hr@name.example' },
    update: {},
    create: {
      email: 'hr@name.example',
      fullName: 'NAME HR Operator',
      role: Role.HR_OPERATOR,
      onboardingState: OnboardingState.COMPLETED,
      entityId: entity.id,
    },
  });

  console.log(`Seed complete: entity ${entity.code} (${entity.id}), HR operator ${operator.email}`);
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
