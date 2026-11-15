import { memo, useEffect, useRef, useState } from 'react';
import { Badge } from '@astryxdesign/core/Badge';
import { Button } from '@astryxdesign/core/Button';
import { Card } from '@astryxdesign/core/Card';
import { HStack } from '@astryxdesign/core/HStack';
import { Heading } from '@astryxdesign/core/Heading';
import { ProgressBar } from '@astryxdesign/core/ProgressBar';
import { Slider } from '@astryxdesign/core/Slider';
import { Text } from '@astryxdesign/core/Text';
import { VStack } from '@astryxdesign/core/VStack';
import { formatTime, formatUptime, type QueueItem, type State } from './api';

const LOG_BOTTOM_SLACK = 24;

interface Props {
  state: State;
  logs: string[];
  act: (action: string, value?: unknown) => void;
  restart: () => void;
}

export default function Dashboard({ state, logs, act, restart }: Props) {
  const np = state.nowPlaying;
  const bot = state.bot;
  const logRef = useRef<HTMLPreElement>(null);
  const [pinned, setPinned] = useState(true);
  const dragging = useRef(false);
  const [volume, setVolume] = useState(np?.volume ?? 0);
  const currentVolume = np?.volume;
  const artwork = np?.artwork;
  const [artworkOk, setArtworkOk] = useState(true);

  // Follow the bot's volume unless the user is dragging the thumb right now.
  useEffect(() => {
    if (!dragging.current && currentVolume !== undefined) setVolume(currentVolume);
  }, [currentVolume]);

  useEffect(() => {
    setArtworkOk(true);
  }, [artwork]);

  // Only follow the log when the reader is already at the bottom; otherwise leave their
  // scroll position alone so they can read an error that just appeared.
  useEffect(() => {
    const element = logRef.current;
    if (element && pinned) element.scrollTop = element.scrollHeight;
  }, [logs, pinned]);

  const onLogScroll = () => {
    const element = logRef.current;
    if (!element) return;
    const distance = element.scrollHeight - element.scrollTop - element.clientHeight;
    setPinned(distance < LOG_BOTTOM_SLACK);
  };

  const jumpToLatest = () => {
    setPinned(true);
    const element = logRef.current;
    if (element) element.scrollTop = element.scrollHeight;
  };

  return (
    <div className="dashboard-grid">
      <VStack gap={4}>
        <Card padding={4}>
          <VStack gap={3}>
            <HStack justify="between" align="center">
              <Heading level={2}>Now playing</Heading>
              {np && <Badge variant="neutral" label={np.source || 'unknown'} />}
            </HStack>

            {np ? (
              <>
                <div className="now-playing">
                  {artwork && artworkOk ? (
                    <img
                      className="artwork"
                      src={artwork}
                      alt=""
                      onError={() => setArtworkOk(false)}
                    />
                  ) : (
                    <div className="artwork" aria-hidden="true" />
                  )}
                  <div className="now-playing-main">
                    <VStack gap={1}>
                      <Heading level={3}>{np.title}</Heading>
                      <Text color="secondary">{np.author}</Text>
                      <HStack gap={2} align="center">
                        <Text size="sm" color="secondary">
                          {np.requester ? `Requested by ${np.requester}` : ''}
                        </Text>
                        {np.favorited && <Badge variant="success" label="Favorite" />}
                        <Badge variant="neutral" label={`Repeat: ${np.repeat}`} />
                      </HStack>
                    </VStack>
                  </div>
                </div>

                <TrackProgress
                  positionMs={np.positionMs}
                  durationMs={np.durationMs}
                  paused={np.paused}
                />

                <div className="controls">
                  <Button
                    label={np.paused ? 'Resume' : 'Pause'}
                    variant="primary"
                    onClick={() => act('pause')}
                  />
                  <Button label="Skip" variant="secondary" onClick={() => act('skip')} />
                  <Button label="Previous" variant="secondary" onClick={() => act('previous')} />
                  <Button
                    label={np.favorited ? 'Unfavorite' : 'Favorite'}
                    variant="secondary"
                    onClick={() => act('favorite')}
                  />
                  <Button label="Shuffle" variant="secondary" onClick={() => act('shuffle')} />
                  <Button label="Repeat" variant="secondary" onClick={() => act('repeat')} />
                  <Button label="Stop" variant="destructive" onClick={() => act('stop')} />
                </div>

                <HStack gap={3} align="center">
                  <Text size="sm" color="secondary">
                    Volume
                  </Text>
                  <div style={{ flex: 1 }}>
                    <Slider
                      label="Volume"
                      isLabelHidden
                      min={0}
                      max={150}
                      step={5}
                      value={volume}
                      onChange={(value: number) => {
                        dragging.current = true;
                        setVolume(value);
                      }}
                      onChangeEnd={(value: number) => {
                        dragging.current = false;
                        setVolume(value);
                        act('volume', value);
                      }}
                    />
                  </div>
                  <Text size="sm">{volume}%</Text>
                </HStack>
              </>
            ) : (
              <Text color="secondary">Nothing is playing.</Text>
            )}
          </VStack>
        </Card>

        <Card padding={4}>
          <VStack gap={3}>
            <HStack justify="between" align="center">
              <Heading level={2}>Queue</Heading>
              <Text size="sm" color="secondary">
                {state.queue.length} track{state.queue.length === 1 ? '' : 's'}
              </Text>
            </HStack>
            {state.queue.length === 0 ? (
              <Text color="secondary">The queue is empty.</Text>
            ) : (
              <VStack gap={0}>
                {state.queue.map((item) => (
                  <QueueRow key={item.id} item={item} />
                ))}
              </VStack>
            )}
          </VStack>
        </Card>
      </VStack>

      <VStack gap={4}>
        <Card padding={4}>
          <VStack gap={3}>
            <HStack justify="between" align="center">
              <Heading level={2}>Status</Heading>
              <Button label="Restart bot" variant="secondary" size="sm" onClick={restart} />
            </HStack>
            <div className="stat-grid">
              <Stat label="Uptime" value={formatUptime(bot.uptimeSeconds)} />
              <Stat label="Guilds" value={String(bot.guilds)} />
              <Stat label="Library" value={`${bot.libraryTracks} tracks`} />
              <Stat label="Cipher" value={bot.cipher} />
              <Stat label="yt-dlp" value={bot.ytdlp ? 'ready' : 'disabled'} />
              <Stat
                label="Heap"
                value={`${state.performance.heapUsedMb} / ${state.performance.heapMaxMb} MB`}
              />
            </div>
          </VStack>
        </Card>

        <Card padding={4}>
          <VStack gap={3}>
            <HStack justify="between" align="center">
              <Heading level={2}>Logs</Heading>
              {!pinned && (
                <Button label="Jump to latest" variant="secondary" size="sm" onClick={jumpToLatest} />
              )}
            </HStack>
            <pre ref={logRef} className="log" onScroll={onLogScroll}>
              {logs.slice(-80).join('\n') || 'No log output yet.'}
            </pre>
          </VStack>
        </Card>
      </VStack>
    </div>
  );
}

/**
 * The bar is advanced from the last polled position, so it moves smoothly instead of jumping
 * once per second. Only this component re-renders on the animation frames.
 */
function TrackProgress({
  positionMs,
  durationMs,
  paused,
}: {
  positionMs: number;
  durationMs: number;
  paused: boolean;
}) {
  const [position, setPosition] = useState(positionMs);
  const base = useRef({ positionMs, startedAt: performance.now() });

  useEffect(() => {
    base.current = { positionMs, startedAt: performance.now() };
    setPosition(positionMs);
  }, [positionMs, paused]);

  useEffect(() => {
    if (paused) return;
    let frame = 0;
    let last = 0;
    const tick = (now: number) => {
      if (now - last >= 100) {
        last = now;
        const start = base.current;
        setPosition(start.positionMs + (now - start.startedAt));
      }
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [paused, durationMs]);

  const max = Math.max(durationMs, 1);
  return (
    <ProgressBar
      label="Track progress"
      isLabelHidden
      value={Math.min(position, max)}
      max={max}
      hasValueLabel
      formatValueLabel={(value, total) => `${formatTime(value)} / ${formatTime(total)}`}
    />
  );
}

const QueueRow = memo(function QueueRow({ item }: { item: QueueItem }) {
  return (
    <div className="queue-row">
      <span className="queue-index">{item.position}</span>
      <span className="queue-title">{item.title}</span>
      <span className="queue-meta">{item.author}</span>
      <span className="queue-meta">{formatTime(item.durationMs)}</span>
    </div>
  );
});

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
    </div>
  );
}
