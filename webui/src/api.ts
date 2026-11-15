export interface BotInfo {
  online: boolean;
  name: string;
  avatar: string;
  guilds: number;
  uptimeSeconds: number;
  version: string;
  libraryTracks: number;
  ytdlp: boolean;
  cipher: string;
}

export interface GuildInfo {
  id: string;
  name: string;
}

export interface NowPlaying {
  title: string;
  author: string;
  uri: string;
  artwork: string;
  durationMs: number;
  positionMs: number;
  paused: boolean;
  volume: number;
  repeat: string;
  favorited: boolean;
  source: string;
  requester: string;
}

export interface QueueItem {
  id: string;
  position: number;
  title: string;
  author: string;
  durationMs: number;
  requester: string;
}

export interface HistoryItem {
  id: string;
  title: string;
  author: string;
  durationMs: number;
}

export interface Performance {
  heapUsedMb: number;
  heapMaxMb: number;
  threads: number;
  uptimeSeconds: number;
}

export interface State {
  bot: BotInfo;
  guilds: GuildInfo[];
  guild: GuildInfo | null;
  nowPlaying: NowPlaying | null;
  queue: QueueItem[];
  history: HistoryItem[];
  performance: Performance;
}

async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url, { cache: 'no-store' });
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`);
  }
  return (await response.json()) as T;
}

export function fetchState(guildId?: string): Promise<State> {
  const query = guildId ? `?guild=${encodeURIComponent(guildId)}` : '';
  return getJson<State>(`/api/state${query}`);
}

export async function fetchLogs(lines = 200): Promise<string[]> {
  const data = await getJson<{ lines: string[] }>(`/api/logs?lines=${lines}`);
  return data.lines;
}

export interface SettingEntry {
  key: string;
  type: 'string' | 'number' | 'boolean' | 'list';
  value: string | number | boolean | string[];
  defaultValue?: string | number | boolean | string[];
  resettable?: boolean;
  /** The server never sends this value back; an empty field means "leave it alone". */
  secret?: boolean;
}

export interface SettingsResponse {
  path: string;
  entries: SettingEntry[];
}

export async function fetchSettings(): Promise<SettingsResponse> {
  return getJson<SettingsResponse>('/api/settings');
}

export async function saveSettings(
  values: Record<string, { type: string; value: unknown }>,
): Promise<void> {
  const response = await fetch('/api/settings', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ values }),
  });
  if (!response.ok) throw await errorOf(response);
}

export async function restartBot(): Promise<void> {
  const response = await fetch('/api/restart', { method: 'POST' });
  // The server says why it could not restart (for example, no launcher next to the jar).
  if (!response.ok) throw await errorOf(response);
}

/** `system` means the user has never picked a side, so the page follows the OS. */
export type ThemeMode = 'light' | 'dark' | 'system';

export async function fetchTheme(): Promise<ThemeMode> {
  const data = await getJson<{ mode: string }>('/api/theme');
  return data.mode === 'dark' || data.mode === 'light' ? data.mode : 'system';
}

export async function saveTheme(mode: 'light' | 'dark'): Promise<void> {
  await fetch('/api/theme', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode }),
  });
}

/** The reason the server gave for refusing a request, instead of its raw JSON. */
async function errorOf(response: Response): Promise<Error> {
  const body = await response.json().catch(() => null);
  return new Error(body?.error ?? `the server answered ${response.status}`);
}

/**
 * Runs a player action on the guild the page is showing.
 *
 * @returns what the bot answered, when it had something to say (for example "Added … to Favorites")
 */
export async function runAction(
  action: string,
  value?: unknown,
  guildId?: string,
): Promise<string | undefined> {
  const query = guildId ? `?guild=${encodeURIComponent(guildId)}` : '';
  const response = await fetch(`/api/action${query}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action, value }),
  });
  if (!response.ok) throw await errorOf(response);
  const body = await response.json().catch(() => null);
  return body?.message;
}

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

export function formatUptime(seconds: number): string {
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
}
