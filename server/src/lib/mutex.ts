/**
 * Minimal promise-based mutex. M1 runs a single Fastify process, so an
 * in-process lock is sufficient to make locate dedupe check+create atomic
 * without relying on unsupported SQLite partial unique indexes.
 */
export class Mutex {
  private tail: Promise<void> = Promise.resolve();

  async run<T>(task: () => Promise<T>): Promise<T> {
    const previous = this.tail;
    let release!: () => void;
    this.tail = new Promise<void>((resolve) => {
      release = resolve;
    });

    await previous;
    try {
      return await task();
    } finally {
      release();
    }
  }
}
