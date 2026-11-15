import { useCallback, useEffect, useRef, useState } from 'react';
import { Theme } from '@astryxdesign/core/theme';
import { neutralTheme } from '@astryxdesign/theme-neutral/built';
import { Avatar } from '@astryxdesign/core/Avatar';
import { Button } from '@astryxdesign/core/Button';
import { Card } from '@astryxdesign/core/Card';
import { HStack } from '@astryxdesign/core/HStack';
import { Heading } from '@astryxdesign/core/Heading';
import { IconButton } from '@astryxdesign/core/IconButton';
import { StatusDot } from '@astryxdesign/core/StatusDot';
import { Tab, TabList } from '@astryxdesign/core/TabList';
import { Text } from '@astryxdesign/core/Text';
import { useToast } from '@astryxdesign/core/Toast';
import { VStack } from '@astryxdesign/core/VStack';
import { fetchLogs, fetchState, fetchTheme, restartBot, runAction, saveTheme, type State } from './api';
import Dashboard from './Dashboard';
import Advanced from './Advanced';
import Dropdown from './Dropdown';

type Mode = 'light' | 'dark';

/** The OS preference, used when the user has never picked a side. */
function systemMode(): Mode {
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/** The theme the server wrote into the document, so the first render matches the first paint. */
function initialMode(): Mode {
  const attribute = document.documentElement.getAttribute('data-theme');
  return attribute === 'dark' ? 'dark' : attribute === 'light' ? 'light' : systemMode();
}

export default function App() {
  const [state, setState] = useState<State | null>(null);
  const [logs, setLogs] = useState<string[]>([]);
  const [tab, setTab] = useState('dashboard');
  const [guildId, setGuildId] = useState<string | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [advancedOpened, setAdvancedOpened] = useState(false);
  const [mode, setMode] = useState<Mode>(initialMode);
  const toast = useToast();
  const toastRef = useRef(toast);
  toastRef.current = toast;
  const stateBusy = useRef(false);
  const logsBusy = useRef(false);

  useEffect(() => {
    let cancelled = false;
    fetchTheme()
      .then((saved) => {
        // "system" keeps the OS-derived theme that the page already painted with.
        if (!cancelled && saved !== 'system') setMode(saved);
      })
      .catch(() => {
        // Keep the theme the page started with when the server is not reachable yet.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const toggleMode = () => {
    const next: Mode = mode === 'dark' ? 'light' : 'dark';
    setMode(next);
    saveTheme(next).catch(() => {
      // The choice still applies for this session.
    });
  };

  const refresh = useCallback(async () => {
    if (stateBusy.current) return;
    stateBusy.current = true;
    try {
      const next = await fetchState(guildId);
      setState((current) => (sameState(current, next) ? current : next));
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      stateBusy.current = false;
    }
  }, [guildId]);

  useEffect(() => {
    refresh();
    const timer = setInterval(() => {
      if (!document.hidden) refresh();
    }, 1000);
    // A hidden window has nothing to draw; refresh once when it comes back.
    const onVisibility = () => {
      if (!document.hidden) refresh();
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [refresh]);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      if (logsBusy.current || document.hidden) return;
      logsBusy.current = true;
      try {
        const lines = await fetchLogs(200);
        if (!cancelled) setLogs((current) => (sameLines(current, lines) ? current : lines));
      } catch {
        // The log endpoint is best effort.
      } finally {
        logsBusy.current = false;
      }
    };
    load();
    const timer = setInterval(load, 2000);
    const onVisibility = () => {
      if (!document.hidden) load();
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      cancelled = true;
      clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, []);

  // The buttons act on the guild whose player is on screen, not on whichever guild the
  // server would pick by itself.
  const shownGuild = guildId ?? state?.guild?.id;
  const act = useCallback(
    async (action: string, value?: unknown) => {
      try {
        const message = await runAction(action, value, shownGuild);
        if (message) toastRef.current({ body: message });
        await refresh();
      } catch (e) {
        // A toast, not the banner: the banner is cleared by the next successful poll, which
        // is less than a second away.
        toastRef.current({ body: e instanceof Error ? e.message : String(e), type: 'error' });
      }
    },
    [refresh, shownGuild],
  );

  const restart = useCallback(async () => {
    try {
      toastRef.current({ body: 'Restarting the bot…', isAutoHide: false });
      await restartBot();
    } catch (e) {
      toastRef.current({
        body: `Restart failed: ${e instanceof Error ? e.message : String(e)}`,
        type: 'error',
      });
    }
  }, []);

  // The Advanced tab stays mounted once it has been opened, so unsaved setting edits survive
  // a visit to the Dashboard. (window.confirm is not an option: the JavaFX WebView has no
  // confirm handler and always answers false.)
  const changeTab = (next: string) => {
    if (next === 'advanced') setAdvancedOpened(true);
    setTab(next);
  };

  const bot = state?.bot;
  const guilds = state?.guilds ?? [];

  return (
    <Theme theme={neutralTheme} mode={mode}>
      <div className="app">
        <header className="app-header">
          <div className="app-title">
            <Avatar src={bot?.avatar} name={bot?.name ?? 'JMusicBot'} size="md" />
            <VStack gap={0}>
              <Heading level={1}>{bot?.name ?? 'JMusicBot'}</Heading>
              <HStack gap={2} align="center">
                <StatusDot
                  variant={bot?.online ? 'success' : 'error'}
                  label={bot?.online ? 'Online' : 'Offline'}
                />
                <Text size="sm" color="secondary">
                  v{bot?.version ?? '0.7.0'}
                </Text>
              </HStack>
            </VStack>
          </div>
          <div className="spacer" />
          <div className="app-actions">
            {guilds.length > 1 && (
              <Dropdown
                ariaLabel="Guild"
                options={guilds.map((guild) => ({ value: guild.id, label: guild.name }))}
                value={guildId ?? state?.guild?.id ?? ''}
                onChange={(value) => setGuildId(value)}
                width={220}
              />
            )}
            <IconButton
              label={mode === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
              icon={mode === 'dark' ? <SunIcon /> : <MoonIcon />}
              variant="ghost"
              size="sm"
              onClick={toggleMode}
            />
            <Button label="Restart" variant="secondary" size="sm" onClick={restart} />
          </div>
          <TabList value={tab} onChange={changeTab} aria-label="Sections" className="app-tabs">
            <Tab value="dashboard" label="Dashboard" />
            <Tab value="advanced" label="Advanced" />
          </TabList>
        </header>

        <main className="app-content">
          {error && (
            <div role="alert">
              <Card padding={3} variant="muted" className="error-banner">
                <Text color="secondary">{error}</Text>
              </Card>
            </div>
          )}
          {!state ? (
            <Text color="secondary">Connecting to the bot…</Text>
          ) : (
            <>
              {tab === 'dashboard' && (
                <Dashboard state={state} logs={logs} act={act} restart={restart} />
              )}
              {advancedOpened && (
                <div hidden={tab !== 'advanced'}>
                  <Advanced state={state} logs={logs} restart={restart} />
                </div>
              )}
            </>
          )}
        </main>
      </div>
    </Theme>
  );
}

function sameState(current: State | null, next: State): boolean {
  return current !== null && JSON.stringify(current) === JSON.stringify(next);
}

function sameLines(current: string[], next: string[]): boolean {
  return (
    current.length === next.length && current.every((line, index) => line === next[index])
  );
}

function SunIcon() {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
    </svg>
  );
}

function MoonIcon() {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
    </svg>
  );
}
