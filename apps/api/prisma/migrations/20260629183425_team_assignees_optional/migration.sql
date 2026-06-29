-- DropForeignKey
ALTER TABLE "teams" DROP CONSTRAINT "teams_hrUserId_fkey";

-- DropForeignKey
ALTER TABLE "teams" DROP CONSTRAINT "teams_managerUserId_fkey";

-- AlterTable
ALTER TABLE "teams" ALTER COLUMN "hrUserId" DROP NOT NULL,
ALTER COLUMN "managerUserId" DROP NOT NULL;

-- AddForeignKey
ALTER TABLE "teams" ADD CONSTRAINT "teams_hrUserId_fkey" FOREIGN KEY ("hrUserId") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "teams" ADD CONSTRAINT "teams_managerUserId_fkey" FOREIGN KEY ("managerUserId") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;
