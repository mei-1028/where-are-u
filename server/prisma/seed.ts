import 'dotenv/config';
import { PrismaClient } from '@prisma/client';
import { generateDeviceToken, hashPassword } from '../src/lib/crypto.ts';

const prisma = new PrismaClient();

const PASSWORD = 'demo12345';
const INVITE_CODE = '824137';
const FAMILY_NAME = '陈家';

const seedUsers = [
  { username: 'demo', nickname: '阿哲', role: 'admin' },
  { username: 'mama', nickname: '妈妈', role: 'member' },
  { username: 'baba', nickname: '爸爸', role: 'member' },
  { username: 'xiaoyu', nickname: '小雨', role: 'member' },
] as const;

const deviceSpecs = [
  {
    username: 'demo',
    name: '阿哲的手机',
    platform: 'android',
    model: 'Xiaomi 15 Pro',
    osVersion: 'Android 15',
    appVersion: '0.1.0',
  },
  {
    username: 'mama',
    name: '妈妈的手机',
    platform: 'android',
    model: 'Pixel 8',
    osVersion: 'Android 14',
    appVersion: '0.1.0',
  },
  {
    username: 'baba',
    name: '爸爸的手机',
    platform: 'android',
    model: 'Huawei Mate 60',
    osVersion: 'HarmonyOS 4.2',
    appVersion: '0.1.0',
  },
  {
    username: 'xiaoyu',
    name: '小雨的手机',
    platform: 'android',
    model: 'Redmi Note 13',
    osVersion: 'Android 14',
    appVersion: '0.1.0',
  },
] as const;

const sampleTracks: Record<string, Array<{ lat: number; lng: number; accuracy: number }>> = {
  demo: [
    { lat: 30.2741, lng: 120.1551, accuracy: 12 },
    { lat: 30.2768, lng: 120.1589, accuracy: 15 },
    { lat: 30.28, lng: 120.1612, accuracy: 10 },
  ],
  mama: [
    { lat: 30.2592, lng: 120.1425, accuracy: 18 },
    { lat: 30.2621, lng: 120.1462, accuracy: 22 },
    { lat: 30.2648, lng: 120.1508, accuracy: 14 },
  ],
  baba: [
    { lat: 30.2887, lng: 120.1723, accuracy: 9 },
    { lat: 30.2851, lng: 120.1688, accuracy: 11 },
    { lat: 30.2819, lng: 120.1644, accuracy: 13 },
  ],
  xiaoyu: [
    { lat: 30.2703, lng: 120.1495, accuracy: 25 },
    { lat: 30.2725, lng: 120.1531, accuracy: 20 },
    { lat: 30.2752, lng: 120.1568, accuracy: 17 },
  ],
};

async function main(): Promise<void> {
  console.log('Seeding demo data ...');

  // Remove only the demo family / demo accounts so the seed is repeatable
  // without wiping unrelated local data.
  const existingFamilies = await prisma.family.findMany({
    where: { OR: [{ inviteCode: INVITE_CODE }, { name: FAMILY_NAME }] },
    select: { id: true },
  });
  for (const family of existingFamilies) {
    await prisma.family.delete({ where: { id: family.id } });
  }

  const usernames = seedUsers.map((user) => user.username);
  await prisma.user.deleteMany({ where: { username: { in: usernames } } });

  const passwordHash = await hashPassword(PASSWORD);
  const now = new Date();
  const inviteExpiresAt = new Date(now.getTime() + 24 * 60 * 60 * 1000);

  const family = await prisma.family.create({
    data: { name: FAMILY_NAME, inviteCode: INVITE_CODE, inviteExpiresAt },
  });

  const usersByUsername = new Map<string, string>();

  for (const spec of seedUsers) {
    const user = await prisma.user.create({
      data: {
        username: spec.username,
        nickname: spec.nickname,
        passwordHash,
      },
    });
    usersByUsername.set(spec.username, user.id);

    await prisma.familyMember.create({
      data: {
        familyId: family.id,
        userId: user.id,
        role: spec.role,
        joinedAt: new Date(now.getTime() - (seedUsers.length - seedUsers.indexOf(spec)) * 60_000),
      },
    });
  }

  for (const spec of deviceSpecs) {
    const userId = usersByUsername.get(spec.username);
    if (!userId) continue;

    const { tokenHash } = generateDeviceToken();
    const device = await prisma.device.create({
      data: {
        userId,
        familyId: family.id,
        name: spec.name,
        platform: spec.platform,
        model: spec.model,
        osVersion: spec.osVersion,
        appVersion: spec.appVersion,
        tokenHash,
        online: false,
      },
    });

    const track = sampleTracks[spec.username] ?? [];
    const points = track.map((point, index) => {
      const recordedAt = new Date(now.getTime() - (track.length - index) * 10 * 60 * 1000);
      return {
        deviceId: device.id,
        userId,
        familyId: family.id,
        lat: point.lat,
        lng: point.lng,
        accuracy: point.accuracy,
        speed: index === track.length - 1 ? 0 : 4.2,
        bearing: 90,
        battery: 86 - index * 3,
        provider: 'amap',
        recordedAt,
      };
    });

    if (points.length > 0) {
      await prisma.location.createMany({ data: points });
      await prisma.device.update({
        where: { id: device.id },
        data: { lastSeenAt: points[points.length - 1]!.recordedAt },
      });
    }
  }

  const memberCount = await prisma.familyMember.count({ where: { familyId: family.id } });
  const deviceCount = await prisma.device.count({ where: { familyId: family.id } });
  const locationCount = await prisma.location.count({ where: { familyId: family.id } });

  console.log('');
  console.log('Seed complete.');
  console.log(`  Family : ${FAMILY_NAME} (${family.id})`);
  console.log(`  Invite : ${INVITE_CODE} (valid 24h, until ${inviteExpiresAt.toISOString()})`);
  console.log(`  Demo credentials: username=demo password=${PASSWORD}`);
  console.log(`  Other members (same password): ${usernames.slice(1).join(', ')}`);
  console.log(`  Created: ${memberCount} members, ${deviceCount} devices, ${locationCount} locations`);
}

main()
  .catch((error: unknown) => {
    console.error('Seed failed:', error instanceof Error ? error.message : error);
    process.exitCode = 1;
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
