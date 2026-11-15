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
import type { BotInfo, NowPlaying, Performance, QueueItem, State } from './api';
import { formatLength, formatTime, formatUptime } from './format';
import LogCard from './LogCard';

/** How often the position is advanced between two polls. */
const TICK_MS = 500;

function wholeSeconds(ms: number): number {
  return Math.floor(ms / 1000) * 1000;
}

type Act = (action: string, value?: unknown) => void;

interface Props {
  state: State;
  logs: string[];
  act: Act;
  /** Asks before stopping; stopping clears the queue. */
  stop: () => void;
}

/**
 * The cards are separate memoised components: the state is polled every second, and only the
 * card whose part of it changed renders again.
 */
export default function Dashboard({ state, logs, act, stop }: Props) {
  return (
    <div className="dashboard-grid">
      <VStack gap={4}>
        <NowPlayingCard np={state.nowPlaying} act={act} stop={stop} />
        <QueueCard queue={state.queue} />
      </VStack>
      <VStack gap={4}>
        <StatusCard bot={state.bot} performance={state.performance} />
        <LogCard lines={logs} limit={80} />
      </VStack>
    </div>
  );
}

const NowPlayingCard = memo(function NowPlayingCard({
  np,
  act,
  stop,
}: {
  np: NowPlaying | null;
  act: Act;
  stop: () => void;
}) {
  const dragging = useRef(false);
  const [volume, setVolume] = useState(np?.volume ?? 0);
  const currentVolume = np?.volume;
  const artwork = np?.artwork;
  // Remembering which picture failed, rather than a flag, needs no reset for the next track.
  const [failedArtwork, setFailedArtwork] = useState<string | null>(null);

  // Follow the bot's volume unless the user is dragging the thumb right now.
  useEffect(() => {
    if (!dragging.current && currentVolume !== undefined) setVolume(currentVolume);
  }, [currentVolume]);


  return (
    <Card padding={4}>
      <VStack gap={3}>
        <HStack justify="between" align="center">
          <Heading level={2}>Now playing</Heading>
          {np && <Badge variant="neutral" label={np.source || 'unknown'} />}
        </HStack>

        {np ? (
          <>
            <div className="now-playing">
              {artwork && artwork !== failedArtwork ? (
                <img
                  className="artwork"
                  src={artwork}
                  alt=""
                  onError={() => setFailedArtwork(artwork)}
                />
              ) : (
                <div className="artwork" aria-hidden="true" />
              )}
              <div className="now-playing-main">
                <VStack gap={1}>
                  <Heading level={3}>{np.title}</Heading>
                  <Text color="secondary">{np.author}</Text>
                  <HStack gap={2} align="center">
                    {np.requester && (
                      <Text size="sm" color="secondary">
                        Requested by {np.requester}
                      </Text>
                    )}
                    {np.stream && <Badge variant="error" label="LIVE" />}
                    {np.favorited && <Badge variant="success" label="Favorite" />}
                    <Badge variant="neutral" label={`Repeat: ${np.repeat}`} />
                  </HStack>
                </VStack>
              </div>
            </div>

            <TrackPosition
              positionMs={np.positionMs}
              durationMs={np.durationMs}
              paused={np.paused}
              stream={np.stream}
              seekable={np.seekable}
              act={act}
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
              <Button label="Stop" variant="destructive" onClick={stop} />
            </div>

            <HStack gap={3} align="center">
              <Text size="sm" color="secondary">
                Volume
              </Text>
              <div className="slider-track">
                <Slider
                  label="Volume"
                  isLabelHidden
                  min={0}
                  max={150}
                  step={5}
                  value={volume}
                  formatValue={(value: number) => `${value}%`}
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
  );
});

/**
 * Where the track is. A track that can be seeked gets a slider; a stream has no length, so it
 * only shows how long it has been playing.
 *
 * <p>The position is advanced locally between polls, on a timer rather than every animation
 * frame: the label changes once a second and the thumb moves a few pixels in that time.
 */
function TrackPosition({
  positionMs,
  durationMs,
  paused,
  stream,
  seekable,
  act,
}: {
  positionMs: number;
  durationMs: number;
  paused: boolean;
  stream: boolean;
  seekable: boolean;
  act: Act;
}) {
  // Whole seconds: that is all the label shows, and an unchanged value skips the render.
  const [position, setPosition] = useState(() => wholeSeconds(positionMs));
  // The effect below stamps the time on mount, before the first tick reads it.
  const base = useRef({ positionMs, startedAt: 0 });
  const dragging = useRef(false);

  useEffect(() => {
    base.current = { positionMs, startedAt: performance.now() };
    if (!dragging.current) setPosition(wholeSeconds(positionMs));
  }, [positionMs, paused]);

  useEffect(() => {
    if (paused) return;
    const timer = setInterval(() => {
      if (dragging.current || document.hidden) return;
      const start = base.current;
      setPosition(wholeSeconds(start.positionMs + (performance.now() - start.startedAt)));
    }, TICK_MS);
    return () => clearInterval(timer);
  }, [paused]);

  if (stream || durationMs <= 0) {
    return (
      <Text size="sm" color="secondary">
        Live stream · playing for {formatTime(position)}
      </Text>
    );
  }

  const shown = Math.min(position, durationMs);
  const label = `${formatTime(shown)} / ${formatTime(durationMs)}`;

  if (!seekable) {
    return (
      <ProgressBar
        label="Track progress"
        isLabelHidden
        value={shown}
        max={durationMs}
        hasValueLabel
        formatValueLabel={() => label}
      />
    );
  }

  return (
    <HStack gap={3} align="center">
      <div className="slider-track">
        <Slider
          label="Position in the track"
          isLabelHidden
          min={0}
          max={durationMs}
          step={1000}
          value={shown}
          formatValue={formatTime}
          onChange={(value: number) => {
            dragging.current = true;
            setPosition(value);
          }}
          onChangeEnd={(value: number) => {
            dragging.current = false;
            base.current = { positionMs: value, startedAt: performance.now() };
            setPosition(value);
            act('seek', value);
          }}
        />
      </div>
      <span className="track-time">{label}</span>
    </HStack>
  );
}

const QueueCard = memo(function QueueCard({ queue }: { queue: QueueItem[] }) {
  return (
    <Card padding={4}>
      <VStack gap={3}>
        <HStack justify="between" align="center">
          <Heading level={2}>Queue</Heading>
          <Text size="sm" color="secondary">
            {queue.length} track{queue.length === 1 ? '' : 's'}
          </Text>
        </HStack>
        {queue.length === 0 ? (
          <Text color="secondary">The queue is empty.</Text>
        ) : (
          // The list role is spelled out: WebKit drops it from a list without markers.
          <ol className="track-list" role="list">
            {queue.map((item) => (
              <QueueRow key={item.id} item={item} />
            ))}
          </ol>
        )}
      </VStack>
    </Card>
  );
});

const QueueRow = memo(function QueueRow({ item }: { item: QueueItem }) {
  return (
    <li className="queue-row">
      <span className="queue-index">{item.position}</span>
      <span className="queue-title">{item.title}</span>
      <span className="queue-meta">{item.author}</span>
      <span className="queue-meta">{formatLength(item.durationMs, item.stream)}</span>
    </li>
  );
});

const StatusCard = memo(function StatusCard({
  bot,
  performance: usage,
}: {
  bot: BotInfo;
  performance: Performance;
}) {
  return (
    <Card padding={4}>
      <VStack gap={3}>
        <Heading level={2}>Status</Heading>
        <div className="stat-grid">
          <Stat label="Uptime" value={formatUptime(bot.uptimeSeconds)} />
          <Stat label="Guilds" value={String(bot.guilds)} />
          <Stat label="Library" value={`${bot.libraryTracks} tracks`} />
          <Stat label="Cipher" value={bot.cipher} />
          <Stat label="yt-dlp" value={bot.ytdlp ? 'ready' : 'disabled'} />
          <Stat label="Heap" value={`${usage.heapUsedMb} / ${usage.heapMaxMb} MB`} />
        </div>
      </VStack>
    </Card>
  );
});

export function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
    </div>
  );
}
