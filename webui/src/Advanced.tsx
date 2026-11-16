import { memo, type Ref } from 'react';
import { Badge } from '@astryxdesign/core/Badge';
import { Card } from '@astryxdesign/core/Card';
import { HStack } from '@astryxdesign/core/HStack';
import { Heading } from '@astryxdesign/core/Heading';
import { ProgressBar } from '@astryxdesign/core/ProgressBar';
import { Text } from '@astryxdesign/core/Text';
import { VStack } from '@astryxdesign/core/VStack';
import type { HistoryItem, State } from './api';
import { Stat } from './Dashboard';
import { formatLength, formatUptime, formatVersion } from './format';
import LogCard from './LogCard';
import SettingsPanel, { type SettingsHandle } from './SettingsPanel';
import UpdatesCard from './UpdatesCard';

interface Props {
  state: State;
  logs: string[];
  /** False while the Dashboard is shown; the tab stays mounted to keep unsaved edits. */
  visible: boolean;
  restartNow: () => void;
  settings: Ref<SettingsHandle>;
}

function Advanced({ state, logs, visible, restartNow, settings }: Props) {
  const usage = state.performance;
  const heapPercent =
    usage.heapMaxMb > 0 ? Math.round((usage.heapUsedMb / usage.heapMaxMb) * 100) : 0;

  return (
    <VStack gap={4}>
      <SettingsPanel restart={restartNow} handle={settings} />

      <UpdatesCard visible={visible} />

      <div className="dashboard-grid">
        <Card padding={4}>
          <VStack gap={3}>
            <Heading level={2}>Performance</Heading>
            <ProgressBar
              label="Heap usage"
              value={usage.heapUsedMb}
              max={Math.max(usage.heapMaxMb, 1)}
              hasValueLabel
              formatValueLabel={(value, max) => `${value} / ${max} MB (${heapPercent}%)`}
            />
            <div className="stat-grid">
              <Stat label="Threads" value={String(usage.threads)} />
              <Stat label="JVM uptime" value={formatUptime(usage.uptimeSeconds)} />
              <Stat label="Bot uptime" value={formatUptime(state.bot.uptimeSeconds)} />
              <Stat label="Queue" value={String(state.queue.length)} />
            </div>
          </VStack>
        </Card>

        <Card padding={4}>
          <VStack gap={3}>
            <Heading level={2}>About</Heading>
            <div className="stat-grid">
              <Stat label="Version" value={formatVersion(state.bot.version)} />
              <Stat label="Library" value={`${state.bot.libraryTracks} tracks`} />
              <Stat label="Cipher" value={state.bot.cipher} />
              <Stat label="yt-dlp" value={state.bot.ytdlp ? 'ready' : 'disabled'} />
            </div>
            <HStack gap={2} align="center">
              <Badge
                variant={state.bot.online ? 'success' : 'error'}
                label={state.bot.online ? 'Online' : 'Offline'}
              />
              <Text size="sm" color="secondary">
                {state.guild?.name ?? 'No guild'}
              </Text>
            </HStack>
          </VStack>
        </Card>
      </div>

      <HistoryCard history={state.history} />
      <LogCard lines={logs} active={visible} />
    </VStack>
  );
}

const HistoryCard = memo(function HistoryCard({ history }: { history: HistoryItem[] }) {
  return (
    <Card padding={4}>
      <VStack gap={3}>
        <Heading level={2}>History</Heading>
        {history.length === 0 ? (
          <Text color="secondary">No tracks have played yet.</Text>
        ) : (
          <ol className="track-list" role="list">
            {history.map((item, index) => (
              <HistoryRow key={item.id} item={item} index={index} />
            ))}
          </ol>
        )}
      </VStack>
    </Card>
  );
});

const HistoryRow = memo(function HistoryRow({
  item,
  index,
}: {
  item: HistoryItem;
  index: number;
}) {
  return (
    <li className="queue-row">
      <span className="queue-index">{index + 1}</span>
      <span className="queue-title">{item.title}</span>
      <span className="queue-meta">{item.author}</span>
      <span className="queue-meta">{formatLength(item.durationMs, item.stream)}</span>
    </li>
  );
});

/**
 * While the tab is hidden it keeps what it last showed: the state is polled every second and
 * there is no point in rendering a tab nobody is looking at. It renders again, with the
 * current state, the moment it is shown.
 */
export default memo(Advanced, (previous, next) => !previous.visible && !next.visible);
