import { memo, useEffect, useRef, useState } from 'react';
import { Button } from '@astryxdesign/core/Button';
import { Card } from '@astryxdesign/core/Card';
import { HStack } from '@astryxdesign/core/HStack';
import { Heading } from '@astryxdesign/core/Heading';
import { VStack } from '@astryxdesign/core/VStack';

const BOTTOM_SLACK = 24;

interface Props {
  lines: string[];
  /** Show only the newest lines. */
  limit?: number;
  /** False while the card is in a hidden tab, where it has no size to scroll in. */
  active?: boolean;
}

/**
 * The tail of the bot's log. It follows new lines only while the reader is at the bottom, so
 * an error they scrolled up to stays where it is. The box can be focused, because a scrolling
 * area that the keyboard cannot reach cannot be read without a mouse.
 */
function LogCard({ lines, limit, active = true }: Props) {
  const boxRef = useRef<HTMLPreElement>(null);
  const [pinned, setPinned] = useState(true);
  const shown = limit ? lines.slice(-limit) : lines;

  useEffect(() => {
    const box = boxRef.current;
    if (box && pinned && active) box.scrollTop = box.scrollHeight;
  }, [lines, pinned, active]);

  const onScroll = () => {
    const box = boxRef.current;
    if (!box) return;
    setPinned(box.scrollHeight - box.scrollTop - box.clientHeight < BOTTOM_SLACK);
  };

  return (
    <Card padding={4}>
      <VStack gap={3}>
        <HStack justify="between" align="center">
          <Heading level={2}>Logs</Heading>
          {!pinned && (
            <Button
              label="Jump to latest"
              variant="secondary"
              size="sm"
              onClick={() => setPinned(true)}
            />
          )}
        </HStack>
        <pre
          ref={boxRef}
          className="log"
          tabIndex={0}
          role="region"
          aria-label="Bot log"
          onScroll={onScroll}
        >
          {shown.join('\n') || 'No log output yet.'}
        </pre>
      </VStack>
    </Card>
  );
}

export default memo(LogCard);
