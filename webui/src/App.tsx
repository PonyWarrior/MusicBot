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
import Advanced from './Advanced';
import ConfirmDialog, { type ConfirmRequest } from './ConfirmDialog';
import Dashboard from './Dashboard';
import Dropdown from './Dropdown';
import { formatVersion } from './format';
import { MoonIcon, SunIcon } from './Icons';
import type { SettingsHandle } from './SettingsPanel';
import { share } from './share';

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

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export default function App() {
  const [state, setState] = useState<State | null>(null);
  const [logs, setLogs] = useState<string[]>([]);
  const [tab, setTab] = useState('dashboard');
  const [guildId, setGuildId] = useState<string | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [advancedOpened, setAdvancedOpened] = useState(false);
  const [mode, setMode] = useState<Mode>(initialMode);
  const [confirm, setConfirm] = useState<ConfirmRequest | null>(null);
  const toast = useToast();
  const toastRef = useRef(toast);
  useEffect(() => {
    toastRef.current = toast;
  });
  const stateBusy = useRef(false);
  const logsBusy = useRef(false);
  const settings = useRef<SettingsHandle>(null);

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

  // The window's own right-click menu offers "Reload page", which would throw unsaved
  // settings away. Text fields keep their menu, for copy and paste.
  useEffect(() => {
    const onContextMenu = (event: MouseEvent) => {
      const target = event.target;
      if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) return;
      event.preventDefault();
    };
    document.addEventListener('contextmenu', onContextMenu);
    return () => document.removeEventListener('contextmenu', onContextMenu);
  }, []);

  const toggleMode = () => {
    const next: Mode = mode === 'dark' ? 'light' : 'dark';
    setMode(next);
    saveTheme(next).catch(() => {
      toastRef.current({
        body: 'The theme could not be saved. It applies until this window is closed.',
        type: 'error',
      });
    });
  };

  const refresh = useCallback(async () => {
    if (stateBusy.current) return;
    stateBusy.current = true;
    try {
      const next = await fetchState(guildId);
      // Parts that did not change keep their identity, so only what changed renders again.
      setState((current) => share(current, next));
      setError(null);
    } catch (e) {
      setError(messageOf(e));
    } finally {
      stateBusy.current = false;
    }
  }, [guildId]);

  useEffect(() => {
    // This starts a request; the state is set when the bot answers, not during the effect.
    // eslint-disable-next-line react-hooks/set-state-in-effect
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
        if (!cancelled) setLogs((current) => share(current, lines));
      } catch {
        // The log endpoint is best effort; the banner already reports a bot that is gone.
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
        toastRef.current({ body: messageOf(e), type: 'error' });
      }
    },
    [refresh, shownGuild],
  );

  const stop = useCallback(() => {
    setConfirm({
      title: 'Stop playback?',
      body: 'This clears the queue and disconnects the bot from the voice channel.',
      actions: [{ label: 'Stop', variant: 'destructive', run: () => act('stop') }],
    });
  }, [act]);

  const restartNow = useCallback(async () => {
    try {
      toastRef.current({ body: 'Restarting the bot…', isAutoHide: false });
      await restartBot();
    } catch (e) {
      toastRef.current({ body: `Restart failed: ${messageOf(e)}`, type: 'error' });
    }
  }, []);

  const requestRestart = () => {
    const unsaved = settings.current?.unsaved() ?? 0;
    const interrupts = 'Playback stops in every server while the bot restarts.';
    if (unsaved === 0) {
      setConfirm({
        title: 'Restart the bot?',
        body: interrupts,
        actions: [{ label: 'Restart', run: restartNow }],
      });
      return;
    }
    setConfirm({
      title: 'Restart the bot?',
      body: `${interrupts} You have ${unsaved} unsaved setting change${
        unsaved === 1 ? '' : 's'
      }, which a restart without saving discards.`,
      actions: [
        { label: 'Restart without saving', variant: 'secondary', run: restartNow },
        {
          label: 'Save and restart',
          run: async () => {
            if (await settings.current?.save({ quiet: true })) restartNow();
            // The form explains what stopped the save, so show it.
            else setTab('advanced');
          },
        },
      ],
    });
  };

  // The Advanced tab stays mounted once it has been opened, so unsaved setting edits survive
  // a visit to the Dashboard.
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
                  {formatVersion(bot?.version)}
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
            <Button label="Restart" variant="secondary" size="sm" onClick={requestRestart} />
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
                <Text color="secondary">{error} Trying again…</Text>
              </Card>
            </div>
          )}
          {!state ? (
            !error && <Text color="secondary">Connecting to the bot…</Text>
          ) : (
            <>
              {tab === 'dashboard' && <Dashboard state={state} logs={logs} act={act} stop={stop} />}
              {advancedOpened && (
                <div hidden={tab !== 'advanced'}>
                  <Advanced
                    state={state}
                    logs={logs}
                    visible={tab === 'advanced'}
                    restartNow={restartNow}
                    settings={settings}
                  />
                </div>
              )}
            </>
          )}
        </main>

        <ConfirmDialog request={confirm} onClose={() => setConfirm(null)} />
      </div>
    </Theme>
  );
}
