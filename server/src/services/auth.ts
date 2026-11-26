import type { PrismaClient, User } from '@prisma/client';
import { hashPassword, verifyPassword } from '../lib/crypto.ts';
import {
  conflict,
  inviteExpired,
  inviteInvalid,
  unauthorized,
} from '../lib/errors.ts';
import { isUniqueConstraintError } from '../lib/prisma.ts';
import { toUserDto, type UserDto } from '../lib/serializers.ts';

export interface RegisterInput {
  username: string;
  nickname: string;
  password: string;
  inviteCode?: string;
}

export interface AuthResult {
  user: UserDto;
  familyId: string | null;
}

export async function registerUser(prisma: PrismaClient, input: RegisterInput): Promise<AuthResult> {
  const existing = await prisma.user.findUnique({ where: { username: input.username } });
  if (existing) throw conflict('Username is already taken');

  let familyId: string | null = null;
  if (input.inviteCode) {
    const family = await prisma.family.findUnique({ where: { inviteCode: input.inviteCode } });
    if (!family) throw inviteInvalid();
    if (family.inviteExpiresAt.getTime() <= Date.now()) throw inviteExpired();
    familyId = family.id;
  }

  const passwordHash = await hashPassword(input.password);

  try {
    const user = await prisma.$transaction(async (tx) => {
      const created = await tx.user.create({
        data: {
          username: input.username,
          nickname: input.nickname,
          passwordHash,
        },
      });

      if (familyId) {
        await tx.familyMember.create({
          data: { familyId, userId: created.id, role: 'member' },
        });
      }

      return created;
    });

    return { user: toUserDto(user), familyId };
  } catch (error) {
    if (isUniqueConstraintError(error)) throw conflict('Username is already taken');
    throw error;
  }
}

export async function authenticateUser(
  prisma: PrismaClient,
  username: string,
  password: string,
): Promise<AuthResult & { user: UserDto }> {
  const user: User | null = await prisma.user.findUnique({ where: { username } });
  if (!user) throw unauthorized('Invalid username or password');

  const valid = await verifyPassword(user.passwordHash, password);
  if (!valid) throw unauthorized('Invalid username or password');

  const membership = await prisma.familyMember.findUnique({ where: { userId: user.id } });

  return { user: toUserDto(user), familyId: membership?.familyId ?? null };
}
