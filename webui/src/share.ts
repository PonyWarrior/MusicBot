function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function idOf(value: unknown): string | undefined {
  return isRecord(value) && typeof value.id === 'string' ? value.id : undefined;
}

/**
 * Returns `next`, reusing every part of `previous` that has the same content.
 *
 * <p>The state is polled once a second and parsed from JSON, so every poll would otherwise
 * hand React a tree of brand-new objects and re-render the whole page even when only the
 * uptime moved. With the unchanged parts kept identical, memoised components (the queue, each
 * row, the history) skip. List items that carry an `id` are matched by it, so a row keeps its
 * identity when the queue shifts.
 */
export function share<T>(previous: unknown, next: T): T {
  if (Object.is(previous, next)) return next;

  if (Array.isArray(previous) && Array.isArray(next)) {
    const byId = new Map<string, unknown>();
    for (const item of previous) {
      const id = idOf(item);
      if (id !== undefined) byId.set(id, item);
    }
    let same = previous.length === next.length;
    const merged = next.map((item, index) => {
      const id = idOf(item);
      const shared = share(id !== undefined && byId.has(id) ? byId.get(id) : previous[index], item);
      if (shared !== previous[index]) same = false;
      return shared;
    });
    return (same ? previous : merged) as T;
  }

  if (isRecord(previous) && isRecord(next)) {
    const keys = Object.keys(next);
    let same = keys.length === Object.keys(previous).length;
    const merged: Record<string, unknown> = {};
    for (const key of keys) {
      merged[key] = share(previous[key], next[key]);
      if (merged[key] !== previous[key] || !(key in previous)) same = false;
    }
    return (same ? previous : merged) as T;
  }

  return next;
}
