import { memo, useCallback, useEffect, useState } from 'react';
import { Badge } from '@astryxdesign/core/Badge';
import { Button } from '@astryxdesign/core/Button';
import { Card } from '@astryxdesign/core/Card';
import { HStack } from '@astryxdesign/core/HStack';
import { Heading } from '@astryxdesign/core/Heading';
import { Text } from '@astryxdesign/core/Text';
import { VStack } from '@astryxdesign/core/VStack';
import { fetchUpdates, runUpdate, type UpdatesResponse } from './api';

/** Only polled while the updater is working; the status call reads versions from disk. */
const BUSY_POLL_MS = 2000;

interface Props {
  /** False while the Advanced tab is hidden. */
  visible: boolean;
}

/**
 * Versions of yt-dlp, FFmpeg, Deno, yt-cipher and the bot, with one button per component and
 * one for everything. Automatic updates and beta releases are switched in the `updates`
 * settings above.
 */
function UpdatesCard({ visible }: Props) {
  const [data, setData] = useState<UpdatesResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    fetchUpdates()
      .then((next) => {
        setData(next);
        setError(null);
      })
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : String(reason)));
  }, []);

  useEffect(() => {
    if (visible) load();
  }, [visible, load]);

  const busy = data?.busy ?? false;
  useEffect(() => {
    if (!visible || !busy) return;
    const timer = window.setInterval(load, BUSY_POLL_MS);
    return () => window.clearInterval(timer);
  }, [visible, busy, load]);

  const start = (action: 'check' | 'update', component?: string) => {
    // Show the busy state at once; the next poll replaces it with the server's view.
    setData((current) => (current ? { ...current, busy: true } : current));
    runUpdate(action, component)
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : String(reason)))
      .finally(load);
  };

  const anyAvailable = data?.components.some((component) => component.updateAvailable) ?? false;

  return (
    <Card padding={4}>
      <VStack gap={3}>
        <HStack justify="between" align="center">
          <Heading level={2}>Updates</Heading>
          <HStack gap={2} align="center">
            <Button
              label="Check now"
              variant="secondary"
              size="sm"
              isDisabled={busy}
              onClick={() => start('check')}
            />
            <Button
              label="Update all"
              size="sm"
              isDisabled={busy || !anyAvailable}
              onClick={() => start('update')}
            />
          </HStack>
        </HStack>

        {error && (
          <Text color="secondary" role="alert">
            {error}
          </Text>
        )}

        {!data ? (
          <Text color="secondary">Loading…</Text>
        ) : (
          <>
            <VStack gap={0}>
              {data.components.map((component) => (
                <div className="queue-row" key={component.id}>
                  <span className="queue-title">{component.name}</span>
                  <span className="queue-meta">
                    {component.present ? component.installed ?? 'unknown' : 'not installed'}
                    {component.latest && component.latest !== component.installed
                      ? ` → ${component.latest}`
                      : ''}
                  </span>
                  {component.updateAvailable ? (
                    <Button
                      label={`Update ${component.name}`}
                      variant="secondary"
                      size="sm"
                      isDisabled={busy}
                      onClick={() => start('update', component.id)}
                    />
                  ) : (
                    // The row is a flex line and the badge may shrink to nothing, so keep its width.
                    <span className="queue-action">
                      <Badge variant="neutral" label={component.note ?? (component.latest ? 'up to date' : 'not checked')} />
                    </span>
                  )}
                </div>
              ))}
            </VStack>
            <Text size="sm" color="secondary" role="status">
              {busy ? 'Working…' : data.lastMessage ?? 'No update has run yet.'}
            </Text>
            <Text size="sm" color="secondary">
              Automatic updates: {data.auto ? 'on' : 'off'} · Beta releases:{' '}
              {data.beta ? 'on' : 'off'} (change them in the updates settings above).
            </Text>
          </>
        )}
      </VStack>
    </Card>
  );
}

export default memo(UpdatesCard);
