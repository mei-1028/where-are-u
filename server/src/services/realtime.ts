/**
 * In-memory registry of connected device sockets. Keeping the registry behind
 * this small interface lets the locate service be tested with a fake socket.
 */
export interface SocketLike {
  readyState: number;
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

const OPEN = 1;

export class RealtimeHub {
  private readonly sockets = new Map<string, SocketLike>();

  /** Registers the newest socket for a device, closing any previous connection. */
  attach(deviceId: string, socket: SocketLike): void {
    const previous = this.sockets.get(deviceId);
    this.sockets.set(deviceId, socket);
    if (previous && previous !== socket) {
      try {
        previous.close(4001, 'Replaced by a newer connection');
      } catch {
        // The old socket may already be closed; nothing to do.
      }
    }
  }

  /**
   * Removes a socket only if it is still the current one. Returns true when
   * this call actually removed the live registration (used to avoid an old
   * socket flipping `online` to false after being replaced).
   */
  detach(deviceId: string, socket: SocketLike): boolean {
    if (this.sockets.get(deviceId) !== socket) return false;
    this.sockets.delete(deviceId);
    return true;
  }

  isConnected(deviceId: string): boolean {
    const socket = this.sockets.get(deviceId);
    return socket !== undefined && socket.readyState === OPEN;
  }

  /** Sends a JSON payload to a device. Returns false when it is not connected. */
  send(deviceId: string, payload: unknown): boolean {
    const socket = this.sockets.get(deviceId);
    if (!socket || socket.readyState !== OPEN) return false;
    try {
      socket.send(JSON.stringify(payload));
      return true;
    } catch {
      return false;
    }
  }

  closeAll(code = 1001, reason = 'Server shutting down'): void {
    for (const socket of this.sockets.values()) {
      try {
        socket.close(code, reason);
      } catch {
        // Ignore sockets that are already closing.
      }
    }
    this.sockets.clear();
  }
}
