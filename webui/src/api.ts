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
  /** Zero for a stream, which has no length. */
  durationMs: number;
  positionMs: number;
  paused: boolean;
  volume: number;
  repeat: string;
  favorited: boolean;
  source: string;
  requester: string;
  stream: boolean;
  seekable: boolean;
}

export interface QueueItem {
  id: string;
  position: number;
  title: string;
  author: string;
  durationMs: number;
  requester: string;
  stream: boolean;
}

export interface HistoryItem {
  id: string;
  title: string;
  author: string;
  durationMs: number;
  stream: boolean;
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

const POLL_TIMEOUT_MS = 5_000;
const REQUEST_TIMEOUT_MS = 15_000;

/**
 * Sends a request that gives up after a while.
 *
 * <p>Without a limit, one request that the bot never answers would hold the poll forever: the
 * page waits for it before sending the next one and would show the last state as if it were
 * current.
 */
async function request(url: string, timeoutMs: number, init: RequestInit = {}): Promise<Response> {
  try {
    return await fetch(url, { cache: 'no-store', ...init, signal: AbortSignal.timeout(timeoutMs) });
  } catch (error) {
    const name = error instanceof Error ? error.name : '';
    throw new Error(
      name === 'TimeoutError' || name === 'AbortError'
        ? 'The bot is not answering.'
        : 'The bot cannot be reached. It may have stopped.',
      { cause: error },
    );
  }
}

/** The reason the server gave for refusing a request, instead of its raw JSON. */
async function errorOf(response: Response): Promise<Error> {
  const body = await response.json().catch(() => null);
  return new Error(body?.error ?? `The bot answered with an error (${response.status}).`);
}

async function getJson<T>(url: string, timeoutMs: number): Promise<T> {
  const response = await request(url, timeoutMs);
  if (!response.ok) throw await errorOf(response);
  try {
    return (await response.json()) as T;
  } catch {
    // The time limit also covers the body, so an answer that stops halfway ends up here.
    throw new Error('The bot sent an answer that could not be read.');
  }
}

function postJson(url: string, body?: unknown): Promise<Response> {
  return request(url, REQUEST_TIMEOUT_MS, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

export function fetchState(guildId?: string): Promise<State> {
  const query = guildId ? `?guild=${encodeURIComponent(guildId)}` : '';
  return getJson<State>(`/api/state${query}`, POLL_TIMEOUT_MS);
}

export async function fetchLogs(lines = 200): Promise<string[]> {
  const data = await getJson<{ lines: string[] }>(`/api/logs?lines=${lines}`, POLL_TIMEOUT_MS);
  return data.lines;
}

export interface SettingEntry {
  key: string;
  type: 'string' | 'number' | 'boolean' | 'list';
  /** Numbers arrive as text so that a long ID keeps every digit. */
  value: string | boolean | string[];
  defaultValue?: string | boolean | string[];
  resettable?: boolean;
  /** The server never sends this value back; an empty field means "leave it alone". */
  secret?: boolean;
  description?: string;
  /** The values the bot accepts, when the setting is a choice. */
  options?: string[];
}

export interface SettingsResponse {
  path: string;
  entries: SettingEntry[];
}

export function fetchSettings(): Promise<SettingsResponse> {
  return getJson<SettingsResponse>('/api/settings', REQUEST_TIMEOUT_MS);
}

export async function saveSettings(
  values: Record<string, { type: string; value: unknown }>,
): Promise<void> {
  const response = await postJson('/api/settings', { values });
  if (!response.ok) throw await errorOf(response);
}

export async function restartBot(): Promise<void> {
  const response = await postJson('/api/restart');
  // The server says why it could not restart (for example, no launcher next to the jar).
  if (!response.ok) throw await errorOf(response);
}

/** `system` means the user has never picked a side, so the page follows the OS. */
export type ThemeMode = 'light' | 'dark' | 'system';

export async function fetchTheme(): Promise<ThemeMode> {
  const data = await getJson<{ mode: string }>('/api/theme', POLL_TIMEOUT_MS);
  return data.mode === 'dark' || data.mode === 'light' ? data.mode : 'system';
}

export async function saveTheme(mode: 'light' | 'dark'): Promise<void> {
  const response = await postJson('/api/theme', { mode });
  if (!response.ok) throw await errorOf(response);
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
  const response = await postJson(`/api/action${query}`, { action, value });
  if (!response.ok) throw await errorOf(response);
  const body = await response.json().catch(() => null);
  return body?.message;
}
