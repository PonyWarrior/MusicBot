import {
  memo,
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
  type FormEvent,
  type Ref,
} from 'react';
import { Button } from '@astryxdesign/core/Button';
import { Card } from '@astryxdesign/core/Card';
import { HStack } from '@astryxdesign/core/HStack';
import { Heading } from '@astryxdesign/core/Heading';
import { Text } from '@astryxdesign/core/Text';
import { useToast } from '@astryxdesign/core/Toast';
import { VStack } from '@astryxdesign/core/VStack';
import { fetchSettings, saveSettings, type SettingEntry } from './api';
import Dropdown from './Dropdown';
import {
  changedKeys,
  fieldId,
  fieldLabel,
  fromEntry,
  isNumberText,
  matchesFilter,
  sectionOf,
  toPayload,
  type FieldValue,
  type Payload,
} from './settings';

/** What the rest of the page may ask of the form, for example before a restart. */
export interface SettingsHandle {
  /** How many fields differ from config.txt. */
  unsaved: () => number;
  /** Saves the changed fields. Resolves to false when something stopped the save. */
  save: (options?: { quiet?: boolean }) => Promise<boolean>;
}

interface Props {
  /** Restarts without asking; used by the "saved" message, which is itself the question. */
  restart: () => void;
  handle?: Ref<SettingsHandle>;
}

type InputNode = HTMLInputElement | HTMLTextAreaElement;

/**
 * The settings form. It is memoized so the one-second state poll in App does not re-render
 * every input while the user is scrolling. Only the values that differ from the file are sent,
 * so a concurrent runtime change is not overwritten.
 */
function SettingsPanel({ restart, handle }: Props) {
  const toast = useToast();
  const [entries, setEntries] = useState<SettingEntry[]>([]);
  const [values, setValues] = useState<Record<string, FieldValue>>({});
  const [initial, setInitial] = useState<Record<string, FieldValue>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [settingsPath, setSettingsPath] = useState('');
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [filter, setFilter] = useState('');
  const inputs = useRef(new Map<string, InputNode>());
  const focusNext = useRef<string | null>(null);

  const fetchNow = useCallback(async () => {
    try {
      const data = await fetchSettings();
      const next: Record<string, FieldValue> = {};
      for (const entry of data.entries) next[entry.key] = fromEntry(entry, entry.value);
      setEntries(data.entries);
      setSettingsPath(data.path);
      setValues(next);
      setInitial(next);
      setErrors({});
      setStatus(null);
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    // This starts a request; the state is set when the bot answers, not during the effect.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    fetchNow();
  }, [fetchNow]);

  const load = () => {
    setLoading(true);
    setLoadError(null);
    return fetchNow();
  };

  // A field hidden by the filter cannot take the focus, so the focus moves after the render
  // that brought it back.
  useEffect(() => {
    if (!focusNext.current) return;
    inputs.current.get(focusNext.current)?.focus();
    focusNext.current = null;
  });

  const dirtyKeys = changedKeys(entries, values, initial);

  const setValue = useCallback((key: string, value: FieldValue) => {
    setValues((current) => ({ ...current, [key]: value }));
    setStatus(null);
    setErrors((current) => {
      if (!(key in current)) return current;
      const next = { ...current };
      delete next[key];
      return next;
    });
  }, []);

  const registerInput = useCallback((key: string, node: InputNode | null) => {
    if (node) inputs.current.set(key, node);
    else inputs.current.delete(key);
  }, []);

  const save = async (options?: { quiet?: boolean }): Promise<boolean> => {
    const nextErrors: Record<string, string> = {};
    for (const entry of entries) {
      if (entry.type !== 'number' || !dirtyKeys.includes(entry.key)) continue;
      if (!isNumberText(String(values[entry.key] ?? '')))
        nextErrors[entry.key] = `${fieldLabel(entry)} must be a number`;
    }
    setErrors(nextErrors);

    const firstInvalid = entries.find((entry) => nextErrors[entry.key]);
    if (firstInvalid) {
      setFilter('');
      focusNext.current = firstInvalid.key;
      setStatus('Fix the highlighted fields before saving.');
      return false;
    }
    if (dirtyKeys.length === 0) return true;

    setSaving(true);
    setStatus(null);
    try {
      const payload: Record<string, Payload> = {};
      for (const entry of entries) {
        if (dirtyKeys.includes(entry.key)) payload[entry.key] = toPayload(entry, values[entry.key]);
      }
      await saveSettings(payload);

      // The saved token is not readable again, so clear the field and rebase the form.
      const saved: Record<string, FieldValue> = { ...values };
      for (const entry of entries) if (entry.secret) saved[entry.key] = '';
      setValues(saved);
      setInitial(saved);
      setStatus('Saved. Restart the bot to apply the new settings.');
      if (!options?.quiet) {
        toast({
          body: 'Settings saved. Restart the bot to apply them.',
          isAutoHide: false,
          endContent: <Button label="Restart" size="sm" onClick={restart} />,
        });
      }
      return true;
    } catch (e) {
      setStatus(e instanceof Error ? e.message : String(e));
      return false;
    } finally {
      setSaving(false);
    }
  };

  // The handle always reaches the newest values without being re-created on every keystroke.
  const latest = useRef({ save, unsaved: dirtyKeys.length });
  useEffect(() => {
    latest.current = { save, unsaved: dirtyKeys.length };
  });
  useImperativeHandle(
    handle,
    () => ({
      unsaved: () => latest.current.unsaved,
      save: (options) => latest.current.save(options),
    }),
    [],
  );

  const discard = () => {
    setValues(initial);
    setErrors({});
    setStatus(null);
  };

  const resetSection = (section: string, sectionEntries: SettingEntry[]) => {
    setValues((current) => {
      const next = { ...current };
      for (const entry of sectionEntries) {
        if (entry.resettable === false || entry.defaultValue === undefined) continue;
        next[entry.key] = fromEntry(entry, entry.defaultValue);
      }
      return next;
    });
    setErrors((current) => {
      const next = { ...current };
      for (const entry of sectionEntries) delete next[entry.key];
      return next;
    });
    toast({ body: `${section} reset to defaults. Press Save to apply.` });
  };

  const groups = new Map<string, SettingEntry[]>();
  for (const entry of entries) {
    if (!matchesFilter(entry, filter)) continue;
    const section = sectionOf(entry);
    const list = groups.get(section) ?? [];
    list.push(entry);
    groups.set(section, list);
  }

  const unsaved =
    dirtyKeys.length === 0
      ? null
      : `${dirtyKeys.length} unsaved change${dirtyKeys.length === 1 ? '' : 's'}`;

  return (
    <form
      className="settings-form"
      noValidate
      onSubmit={(event: FormEvent) => {
        event.preventDefault();
        save();
      }}
    >
      <VStack gap={4}>
        <Card padding={4}>
          <VStack gap={3}>
            <div className="settings-header">
              <Heading level={2}>Settings</Heading>
              <div className="settings-actions">
                <span role="status" className="settings-status">
                  {(status ?? unsaved) && (
                    <Text size="sm" color="secondary">
                      {status ?? unsaved}
                    </Text>
                  )}
                </span>
                {dirtyKeys.length > 0 && (
                  <Button label="Discard changes" variant="secondary" onClick={discard} />
                )}
                <Button
                  type="submit"
                  label={saving ? 'Saving…' : 'Save'}
                  variant="primary"
                  isDisabled={saving || dirtyKeys.length === 0}
                />
              </div>
            </div>
            <Text size="sm" color="secondary">
              Changes are written to config.txt and take effect when the bot restarts.
            </Text>
            {settingsPath && (
              <Text size="sm" color="secondary">
                {settingsPath}
              </Text>
            )}
            {entries.length > 0 && (
              <div className="setting-field settings-filter">
                <label className="setting-label" htmlFor="settings-filter">
                  Filter settings
                </label>
                <input
                  id="settings-filter"
                  className="setting-input"
                  type="search"
                  value={filter}
                  autoComplete="off"
                  spellCheck={false}
                  onChange={(event) => setFilter(event.target.value)}
                />
              </div>
            )}
          </VStack>
        </Card>

        {loading && entries.length === 0 && <Text color="secondary">Loading the settings…</Text>}

        {loadError && (
          <Card padding={4}>
            <div role="alert" className="settings-load-error">
              <Text>The settings could not be loaded. {loadError}</Text>
              <Button label="Retry" variant="secondary" onClick={load} isDisabled={loading} />
            </div>
          </Card>
        )}

        {entries.length > 0 && groups.size === 0 && (
          <Text color="secondary">No setting matches “{filter.trim()}”.</Text>
        )}

        {[...groups.entries()].map(([section, sectionEntries]) => (
          <Card padding={4} key={section}>
            <VStack gap={3}>
              <HStack justify="between" align="center">
                <Heading level={3}>{section}</Heading>
                <Button
                  label="Reset"
                  variant="secondary"
                  size="sm"
                  aria-label={`Reset ${section} to defaults`}
                  onClick={() => resetSection(section, sectionEntries)}
                />
              </HStack>
              <div className="settings-grid">
                {sectionEntries.map((entry) => (
                  <SettingField
                    key={entry.key}
                    entry={entry}
                    value={values[entry.key] ?? ''}
                    error={errors[entry.key]}
                    onChange={setValue}
                    registerInput={registerInput}
                  />
                ))}
              </div>
            </VStack>
          </Card>
        ))}
      </VStack>
    </form>
  );
}

const SettingField = memo(function SettingField({
  entry,
  value,
  error,
  onChange,
  registerInput,
}: {
  entry: SettingEntry;
  value: FieldValue;
  error?: string;
  onChange: (key: string, value: FieldValue) => void;
  registerInput: (key: string, node: InputNode | null) => void;
}) {
  const label = fieldLabel(entry);
  const id = fieldId(entry);
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;

  const hints = [
    entry.description,
    entry.type === 'list' ? 'One item per line.' : undefined,
    entry.secret ? 'Leave empty to keep the current value.' : undefined,
  ].filter(Boolean);
  const hint = hints.length > 0 && (
    <span className="setting-hint" id={hintId}>
      {hints.join(' ')}
    </span>
  );
  const describedBy =
    [hints.length > 0 ? hintId : null, error ? errorId : null].filter(Boolean).join(' ') ||
    undefined;
  const problem = error && (
    <span className="setting-error" id={errorId} role="alert">
      {error}
    </span>
  );

  if (entry.type === 'boolean') {
    return (
      <div className="setting-field">
        <span className="setting-label" id={`${id}-label`}>
          {label}
        </span>
        <label className="setting-check">
          <input
            id={id}
            type="checkbox"
            checked={Boolean(value)}
            aria-labelledby={`${id}-label`}
            aria-describedby={describedBy}
            onChange={(event) => onChange(entry.key, event.target.checked)}
          />
          <span aria-hidden="true">{value ? 'Enabled' : 'Disabled'}</span>
        </label>
        {hint}
      </div>
    );
  }

  if (entry.options) {
    return (
      <div className="setting-field">
        <Dropdown
          label={label}
          options={entry.options.map((option) => ({ value: option, label: option }))}
          value={String(value)}
          describedBy={describedBy}
          onChange={(next) => onChange(entry.key, next)}
        />
        {hint}
      </div>
    );
  }

  if (entry.type === 'list') {
    return (
      <div className="setting-field">
        <label className="setting-label" htmlFor={id}>
          {label}
        </label>
        <textarea
          id={id}
          className="setting-input setting-textarea"
          rows={3}
          value={String(value)}
          spellCheck={false}
          autoComplete="off"
          autoCapitalize="none"
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          ref={(node) => registerInput(entry.key, node)}
          onChange={(event) => onChange(entry.key, event.target.value)}
        />
        {problem}
        {hint}
      </div>
    );
  }

  return (
    <div className="setting-field">
      <label className="setting-label" htmlFor={id}>
        {label}
      </label>
      <input
        id={id}
        className="setting-input"
        type={entry.secret ? 'password' : 'text'}
        inputMode={entry.type === 'number' ? 'decimal' : undefined}
        value={String(value)}
        autoComplete={entry.secret ? 'new-password' : 'off'}
        autoCapitalize="none"
        spellCheck={false}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        ref={(node) => registerInput(entry.key, node)}
        onChange={(event) => onChange(entry.key, event.target.value)}
      />
      {problem}
      {hint}
    </div>
  );
});

export default memo(SettingsPanel);
