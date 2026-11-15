import { useEffect, useId, useRef } from 'react';
import { Button } from '@astryxdesign/core/Button';
import { Heading } from '@astryxdesign/core/Heading';
import { Text } from '@astryxdesign/core/Text';

export interface ConfirmAction {
  label: string;
  variant?: 'primary' | 'secondary' | 'destructive';
  run: () => void;
}

export interface ConfirmRequest {
  title: string;
  body: string;
  /** The ways to go ahead. Cancel is always offered and is where the focus starts. */
  actions: ConfirmAction[];
}

interface Props {
  request: ConfirmRequest | null;
  onClose: () => void;
}

/**
 * Asks before an action that cannot be taken back.
 *
 * <p>It is a native modal `<dialog>`: the browser traps the focus, makes the page behind it
 * inert, closes on Escape and returns the focus to the button that opened it.
 * `window.confirm` cannot be used, because the JavaFX WebView has no handler for it and
 * answers "no" without showing anything.
 */
export default function ConfirmDialog({ request, onClose }: Props) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const titleId = useId();
  const bodyId = useId();

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (request && !dialog.open) {
      dialog.showModal();
      // The safe choice takes the focus, so Enter on a just-opened dialog does nothing drastic.
      cancelRef.current?.focus();
    } else if (!request && dialog.open) {
      dialog.close();
    }
  }, [request]);

  return (
    <dialog
      ref={dialogRef}
      className="confirm-dialog"
      aria-labelledby={titleId}
      aria-describedby={bodyId}
      onClose={onClose}
    >
      {request && (
        <>
          <Heading level={2} id={titleId}>
            {request.title}
          </Heading>
          <p id={bodyId} className="confirm-body">
            <Text color="secondary">{request.body}</Text>
          </p>
          <div className="confirm-actions">
            <Button ref={cancelRef} label="Cancel" variant="secondary" onClick={onClose} />
            {request.actions.map((action) => (
              <Button
                key={action.label}
                label={action.label}
                variant={action.variant ?? 'primary'}
                onClick={() => {
                  onClose();
                  action.run();
                }}
              />
            ))}
          </div>
        </>
      )}
    </dialog>
  );
}
