import type { Family, FamilyMember, PrismaClient } from '@prisma/client';
import { forbidden, notFound } from '../lib/errors.ts';

export type MembershipWithFamily = FamilyMember & { family: Family };

/**
 * Resolves the caller's family membership. All family-scoped endpoints derive
 * familyId from this server-side lookup rather than from client input.
 */
export async function requireMembership(
  prisma: PrismaClient,
  userId: string,
): Promise<MembershipWithFamily> {
  const membership = await prisma.familyMember.findUnique({
    where: { userId },
    include: { family: true },
  });
  if (!membership) throw notFound('You are not in a family');
  return membership;
}

export function requireAdmin(membership: Pick<FamilyMember, 'role'>): void {
  if (membership.role !== 'admin') {
    throw forbidden('Only family admins can do this');
  }
}
