import type { Device, Location, User } from '@prisma/client';

export interface UserDto {
  id: string;
  username: string;
  nickname: string;
}

export interface LastLocationDto {
  lat: number;
  lng: number;
  accuracy: number;
  battery: number | null;
  recordedAt: string;
}

export interface DeviceDto {
  id: string;
  /** Additive field (not in the contract table) so the web device list can show the owner. */
  userId: string;
  name: string;
  platform: string;
  model: string;
  osVersion: string;
  appVersion: string;
  online: boolean;
  lastSeenAt: string | null;
  lastLocation: LastLocationDto | null;
}

export function toUserDto(user: Pick<User, 'id' | 'username' | 'nickname'>): UserDto {
  return { id: user.id, username: user.username, nickname: user.nickname };
}

export function toLastLocation(
  location: Pick<Location, 'lat' | 'lng' | 'accuracy' | 'battery' | 'recordedAt'> | null | undefined,
): LastLocationDto | null {
  if (!location) return null;
  return {
    lat: location.lat,
    lng: location.lng,
    accuracy: location.accuracy,
    battery: location.battery ?? null,
    recordedAt: location.recordedAt.toISOString(),
  };
}

export function toDeviceDto(device: Device, lastLocation?: Location | null): DeviceDto {
  return {
    id: device.id,
    userId: device.userId,
    name: device.name,
    platform: device.platform,
    model: device.model,
    osVersion: device.osVersion,
    appVersion: device.appVersion,
    online: device.online,
    lastSeenAt: device.lastSeenAt ? device.lastSeenAt.toISOString() : null,
    lastLocation: toLastLocation(lastLocation),
  };
}
