/** A track position or length as `m:ss` or `h:mm:ss`. */
export function formatTime(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '0:00';
  const total = Math.floor(ms / 1000);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  if (hours > 0) {
    return `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
  }
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

/** The length of a queued track; a stream has none. */
export function formatLength(durationMs: number, stream: boolean | undefined): string {
  return stream ? 'LIVE' : formatTime(durationMs);
}

export function formatUptime(seconds: number): string {
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
}

/** The version as the header shows it; a build run from classes has no version. */
export function formatVersion(version: string | undefined): string {
  if (!version) return '';
  return version === 'UNKNOWN' ? 'development build' : `v${version}`;
}
