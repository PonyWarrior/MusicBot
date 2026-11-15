import { memo } from 'react';
import { Badge } from '@astryxdesign/core/Badge';
import { Card } from '@astryxdesign/core/Card';
import { HStack } from '@astryxdesign/core/HStack';
import { Heading } from '@astryxdesign/core/Heading';
import { ProgressBar } from '@astryxdesign/core/ProgressBar';
import { Text } from '@astryxdesign/core/Text';
import { VStack } from '@astryxdesign/core/VStack';
import { formatTime, formatUptime, type HistoryItem, type State } from './api';
import SettingsPanel from './SettingsPanel';

interface Props {
  state: State;
  logs: string[];
  restart: () => void;
}

export default function Advanced({ state, logs, restart }: Props) {
  const heapPercent =
    state.performance.heapMaxMb > 0
      ? Math.round((state.performance.heapUsedMb / state.performance.heapMaxMb) * 100)
      : 0;

  return (
    <VStack gap={4}>
      <SettingsPanel restart={restart} />

      <div className="dashboard-grid">
        <Card padding={4}>
          <VStack gap={3}>
            <Heading level={2}>Performance</Heading>
            <ProgressBar
              label="Heap usage"
              value={state.performance.heapUsedMb}
              max={Math.max(state.performance.heapMaxMb, 1)}
              hasValueLabel
              formatValueLabel={(value, max) => `${value} / ${max} MB (${heapPercent}%)`}
            />
            <div className="stat-grid">
              <Stat label="Threads" value={String(state.performance.threads)} />
              <Stat label="JVM uptime" value={formatUptime(state.performance.uptimeSeconds)} />
              <Stat label="Bot uptime" value={formatUptime(state.bot.uptimeSeconds)} />
              <Stat label="Queue" value={String(state.queue.length)} />
            </div>
          </VStack>
        </Card>

        <Card padding={4}>
          <VStack gap={3}>
            <Heading level={2}>About</Heading>
            <div className="stat-grid">
              <Stat label="Version" value={state.bot.version} />
              <Stat label="Library" value={`${state.bot.libraryTracks} tracks`} />
              <Stat label="Cipher" value={state.bot.cipher} />
              <Stat label="yt-dlp" value={state.bot.ytdlp ? 'ready' : 'disabled'} />
            </div>
            <HStack gap={2} align="center">
              <Badge
                variant={state.bot.online ? 'success' : 'error'}
                label={state.bot.online ? 'Online' : 'Offline'}
              />
              <Text size="sm" color="secondary">{state.guild?.name ?? 'No guild'}</Text>
            </HStack>
          </VStack>
        </Card>
      </div>

      <Card padding={4}>
        <VStack gap={3}>
          <Heading level={2}>History</Heading>
          {state.history.length === 0 ? (
            <Text color="secondary">No tracks have played yet.</Text>
          ) : (
            <VStack gap={0}>
              {state.history.map((item, index) => (
                <HistoryRow key={item.id} item={item} index={index} />
              ))}
            </VStack>
          )}
        </VStack>
      </Card>

      <Card padding={4}>
        <VStack gap={3}>
          <Heading level={2}>Logs</Heading>
          <pre className="log">{logs.join('\n') || 'No log output yet.'}</pre>
        </VStack>
      </Card>
    </VStack>
  );
}

const HistoryRow = memo(function HistoryRow({
  item,
  index,
}: {
  item: HistoryItem;
  index: number;
}) {
  return (
    <div className="queue-row">
      <span className="queue-index">{index + 1}</span>
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
