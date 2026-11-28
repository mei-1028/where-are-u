import type { Place, PrismaClient } from '@prisma/client';
import { conflict, forbidden, notFound, validationError } from '../lib/errors.ts';
import { isUniqueConstraintError } from '../lib/prisma.ts';
import type { GeofenceConfig } from '../lib/validation.ts';
import { resetGeofenceStateForPlace } from './follows.ts';
import { requireMembership, type MembershipWithFamily } from './memberships.ts';

export interface PlaceDto {
  id: string;
  name: string;
  type: string;
  lat: number;
  lng: number;
  radiusMeters: number;
  createdBy: string;
  createdAt: string;
  /** How many geofence rules in the family reference this place. */
  usedByRules: number;
}

export interface PlaceInput {
  name: string;
  type: string;
  lat: number;
  lng: number;
  radiusMeters: number;
}

export type PlacePatch = Partial<PlaceInput>;

const MAX_PLACES_PER_FAMILY = 20;

function normalizeNameKey(name: string): string {
  return name.trim().normalize('NFKC').toLowerCase();
}

function toPlaceDto(place: Place, usedByRules: number): PlaceDto {
  return {
    id: place.id,
    name: place.name,
    type: place.type,
    lat: place.lat,
    lng: place.lng,
    radiusMeters: place.radiusMeters,
    createdBy: place.createdBy,
    createdAt: place.createdAt.toISOString(),
    usedByRules,
  };
}

function placeIdOf(configJson: string): string | null {
  try {
    const config = JSON.parse(configJson) as Partial<GeofenceConfig>;
    return typeof config.placeId === 'string' ? config.placeId : null;
  } catch {
    return null;
  }
}

async function loadGeofenceConfigs(
  prisma: PrismaClient,
  familyId: string,
): Promise<string[]> {
  const rules = await prisma.alertRule.findMany({
    where: { type: 'GEOFENCE', follow: { familyId } },
    select: { config: true },
  });
  return rules.map((rule) => rule.config);
}

function countByPlace(configs: string[]): Map<string, number> {
  const counts = new Map<string, number>();
  for (const config of configs) {
    const placeId = placeIdOf(config);
    if (!placeId) continue;
    counts.set(placeId, (counts.get(placeId) ?? 0) + 1);
  }
  return counts;
}

export async function listPlaces(
  prisma: PrismaClient,
  userId: string,
): Promise<PlaceDto[]> {
  const membership = await requireMembership(prisma, userId);
  const [places, configs] = await Promise.all([
    prisma.place.findMany({
      where: { familyId: membership.familyId },
      orderBy: { createdAt: 'asc' },
    }),
    loadGeofenceConfigs(prisma, membership.familyId),
  ]);
  const counts = countByPlace(configs);
  return places.map((place) => toPlaceDto(place, counts.get(place.id) ?? 0));
}

export async function createPlace(
  prisma: PrismaClient,
  userId: string,
  input: PlaceInput,
): Promise<PlaceDto> {
  const membership = await requireMembership(prisma, userId);
  const count = await prisma.place.count({
    where: { familyId: membership.familyId },
  });
  if (count >= MAX_PLACES_PER_FAMILY) {
    throw validationError(`A family can have at most ${MAX_PLACES_PER_FAMILY} places`);
  }

  try {
    const place = await prisma.place.create({
      data: {
        familyId: membership.familyId,
        createdBy: userId,
        name: input.name,
        nameKey: normalizeNameKey(input.name),
        type: input.type,
        lat: input.lat,
        lng: input.lng,
        radiusMeters: input.radiusMeters,
      },
    });
    return toPlaceDto(place, 0);
  } catch (error) {
    if (isUniqueConstraintError(error)) {
      throw conflict('A place with this name already exists');
    }
    throw error;
  }
}

interface ManagedPlace {
  place: Place;
  membership: MembershipWithFamily;
}

async function requireManagedPlace(
  prisma: PrismaClient,
  userId: string,
  placeId: string,
): Promise<ManagedPlace> {
  const membership = await requireMembership(prisma, userId);
  const place = await prisma.place.findFirst({
    where: { id: placeId, familyId: membership.familyId },
  });
  // 404 first so a cross-family id cannot be probed; only then check the role.
  if (!place) throw notFound('Place not found');
  if (place.createdBy !== userId && membership.role !== 'admin') {
    throw forbidden('Only the creator or a family admin can change this place');
  }
  return { place, membership };
}

export async function updatePlace(
  prisma: PrismaClient,
  userId: string,
  placeId: string,
  patch: PlacePatch,
): Promise<PlaceDto> {
  const { place, membership } = await requireManagedPlace(prisma, userId, placeId);

  const geometryChanged =
    (patch.lat !== undefined && patch.lat !== place.lat) ||
    (patch.lng !== undefined && patch.lng !== place.lng) ||
    (patch.radiusMeters !== undefined && patch.radiusMeters !== place.radiusMeters);

  try {
    const updated = await prisma.$transaction(async (tx) => {
      const next = await tx.place.update({
        where: { id: place.id },
        data: {
          ...(patch.name !== undefined
            ? { name: patch.name, nameKey: normalizeNameKey(patch.name) }
            : {}),
          ...(patch.type !== undefined ? { type: patch.type } : {}),
          ...(patch.lat !== undefined ? { lat: patch.lat } : {}),
          ...(patch.lng !== undefined ? { lng: patch.lng } : {}),
          ...(patch.radiusMeters !== undefined
            ? { radiusMeters: patch.radiusMeters }
            : {}),
        },
      });
      if (geometryChanged) {
        await resetGeofenceStateForPlace(tx, membership.familyId, place.id);
      }
      return next;
    });

    const configs = await loadGeofenceConfigs(prisma, membership.familyId);
    return toPlaceDto(updated, countByPlace(configs).get(place.id) ?? 0);
  } catch (error) {
    if (isUniqueConstraintError(error)) {
      throw conflict('A place with this name already exists');
    }
    throw error;
  }
}

export async function deletePlace(
  prisma: PrismaClient,
  userId: string,
  placeId: string,
): Promise<{ removedRules: number }> {
  const { place, membership } = await requireManagedPlace(prisma, userId, placeId);

  return prisma.$transaction(async (tx) => {
    const rules = await tx.alertRule.findMany({
      where: { type: 'GEOFENCE', follow: { familyId: membership.familyId } },
      select: { id: true, config: true },
    });
    const ruleIds = rules
      .filter((rule) => placeIdOf(rule.config) === place.id)
      .map((rule) => rule.id);
    if (ruleIds.length > 0) {
      await tx.alertRule.deleteMany({ where: { id: { in: ruleIds } } });
    }
    await tx.place.delete({ where: { id: place.id } });
    return { removedRules: ruleIds.length };
  });
}
