import { memo, useEffect, useRef, useState, type FormEvent } from 'react';
import { Button } from '@astryxdesign/core/Button';
import { Card } from '@astryxdesign/core/Card';
import { HStack } from '@astryxdesign/core/HStack';
import { Heading } from '@astryxdesign/core/Heading';
import { Text } from '@astryxdesign/core/Text';
import { useToast } from '@astryxdesign/core/Toast';
import { VStack } from '@astryxdesign/core/VStack';
import { fetchSettings, saveSettings, type SettingEntry } from './api';
import Dropdown from './Dropdown';

const ENUMS: Record<string, string[]> = {
  'logging.level': ['off', 'error', 'warn', 'info', 'debug', 'trace', 'all'],
  'gui.mode': ['web', 'swing'],
  'gui.theme': ['light', 'dark', 'darcula', 'intellij'],
  'performance.resamplingQuality': ['low', 'medium', 'high'],
  'presence.status': ['ONLINE', 'IDLE', 'DND', 'INVISIBLE'],
};

type FieldValue = string | boolean;

interface Payload {
  type: string;
  value: unknown;
}

interface Props {
  restart: () => void;
}

function fieldLabel(entry: SettingEntry): string {
  return entry.key.includes('.')
    ? entry.key.substring(entry.key.indexOf('.') + 1)
    : entry.key;
}

function fieldId(entry: SettingEntry): string {
  return `setting-${entry.key.replace(/[^a-zA-Z0-9]+/g, '-')}`;
}

/** Lists are edited one item per line, so an entry may safely contain a comma. */
function fromEntry(entry: SettingEntry, value: unknown): FieldValue {
  if (entry.type === 'boolean') return Boolean(value);
  if (entry.type === 'list')
    return Array.isArray(value) ? value.join('\n') : String(value ?? '');
  return String(value ?? '');
}

function toPayload(entry: SettingEntry, value: FieldValue): Payload {
  if (entry.type === 'boolean') return { type: 'boolean', value: Boolean(value) };
  if (entry.type === 'number') return { type: 'number', value: Number(value) };
  if (entry.type === 'list')
    return {
      type: 'list',
      value: String(value)
        .split('\n')
        .map((line) => line.trim())
        .filter(Boolean),
    };
  return { type: 'string', value: String(value) };
}

/**
 * The settings form. It is memoized so the one-second state poll in App does not re-render
 * every input while the user is scrolling. Only the values that differ from the file are sent,
 * so a concurrent runtime change is not overwritten.
 */
function SettingsPanel({ restart }: Props) {
  const toast = useToast();
  const [entries, setEntries] = useState<SettingEntry[]>([]);
  const [values, setValues] = useState<Record<string, FieldValue>>({});
  const [initial, setInitial] = useState<Record<string, FieldValue>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [settingsPath, setSettingsPath] = useState('');
  const [status, setStatus] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const inputs = useRef(new Map<string, HTMLInputElement | HTMLTextAreaElement>());

  useEffect(() => {
    let cancelled = false;
    fetchSettings()
      .then((data) => {
        if (cancelled) return;
        setEntries(data.entries);
        setSettingsPath(data.path);
        const next: Record<string, FieldValue> = {};
        for (const entry of data.entries) next[entry.key] = fromEntry(entry, entry.value);
        setValues(next);
        setInitial(next);
      })
      .catch((e) => {
        if (!cancelled) setStatus(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const dirtyKeys = entries
    .filter((entry) =>
      entry.secret
        ? String(values[entry.key] ?? '').length > 0
        : values[entry.key] !== initial[entry.key],
    )
    .map((entry) => entry.key);

  const setValue = (key: string, value: FieldValue) => {
    setValues((current) => ({ ...current, [key]: value }));
    setErrors((current) => {
      if (!(key in current)) return current;
      const next = { ...current };
      delete next[key];
      return next;
    });
  };

  const registerInput = (
    key: string,
    node: HTMLInputElement | HTMLTextAreaElement | null,
  ) => {
    if (node) inputs.current.set(key, node);
    else inputs.current.delete(key);
  };

  const save = async (event?: FormEvent) => {
    event?.preventDefault();

    const nextErrors: Record<string, string> = {};
    for (const entry of entries) {
      if (entry.type !== 'number' || !dirtyKeys.includes(entry.key)) continue;
      const text = String(values[entry.key] ?? '').trim();
      if (text === '' || !Number.isFinite(Number(text)))
        nextErrors[entry.key] = `${fieldLabel(entry)} must be a number`;
    }
    setErrors(nextErrors);

    const firstInvalid = entries.find((entry) => nextErrors[entry.key]);
    if (firstInvalid) {
      inputs.current.get(firstInvalid.key)?.focus();
      setStatus('Fix the highlighted fields before saving.');
      return;
    }
    if (dirtyKeys.length === 0) {
      setStatus('No changes to save.');
      return;
    }

    setSaving(true);
    setStatus(null);
    try {
      const payload: Record<string, Payload> = {};
      for (const entry of entries) {
        if (!dirtyKeys.includes(entry.key)) continue;
        // An untouched secret field is empty; sending it would erase the token.
        if (entry.secret && String(values[entry.key] ?? '').length === 0) continue;
        payload[entry.key] = toPayload(entry, values[entry.key]);
      }
      await saveSettings(payload);

      // The saved token is not readable again, so clear the field and rebase the form.
      const saved: Record<string, FieldValue> = { ...values };
      for (const entry of entries) if (entry.secret) saved[entry.key] = '';
      setValues(saved);
      setInitial(saved);
      setStatus('Saved. Restart the bot to apply the new settings.');
      toast({
        body: 'Settings saved. Restart the bot to apply them.',
        isAutoHide: false,
        endContent: <Button label="Restart" size="sm" onClick={restart} />,
      });
    } catch (e) {
      setStatus(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
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
    const section = entry.key.split('.')[0];
    const list = groups.get(section) ?? [];
    list.push(entry);
    groups.set(section, list);
  }

  return (
    <form className="settings-form" onSubmit={save} noValidate>
      <VStack gap={4}>
        <Card padding={4}>
          <VStack gap={3}>
            <HStack justify="between" align="center">
              <Heading level={2}>Settings</Heading>
              <HStack gap={2} align="center">
                {status && (
                  <span role="status" className="settings-status">
                    <Text size="sm" color="secondary">
                      {status}
                    </Text>
                  </span>
                )}
                <Button
                  type="submit"
                  label={saving ? 'Saving…' : 'Save'}
                  variant="primary"
                  isDisabled={saving}
                />
              </HStack>
            </HStack>
            <Text size="sm" color="secondary">
              Every setting is a field below. Changes are written to config.txt and need a restart.
            </Text>
            <Text size="sm" color="secondary">{settingsPath}</Text>
          </VStack>
        </Card>

        {[...groups.entries()].map(([section, sectionEntries]) => (
          <Card padding={4} key={section}>
            <VStack gap={3}>
              <HStack justify="between" align="center">
                <Heading level={3}>{section}</Heading>
                <Button
                  label="Reset"
                  variant="secondary"
                  size="sm"
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
                    onChange={(value) => setValue(entry.key, value)}
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

function SettingField({
  entry,
  value,
  error,
  onChange,
  registerInput,
}: {
  entry: SettingEntry;
  value: FieldValue;
  error?: string;
  onChange: (value: FieldValue) => void;
  registerInput: (key: string, node: HTMLInputElement | HTMLTextAreaElement | null) => void;
}) {
  const label = fieldLabel(entry);
  const id = fieldId(entry);
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const describedBy = error ? errorId : entry.secret ? hintId : undefined;

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
            onChange={(event) => onChange(event.target.checked)}
          />
          <span aria-hidden="true">{value ? 'Enabled' : 'Disabled'}</span>
        </label>
      </div>
    );
  }

  const options = ENUMS[entry.key];
  if (options) {
    return (
      <Dropdown
        label={label}
        options={options.map((option) => ({ value: option, label: option }))}
        value={String(value)}
        onChange={onChange}
      />
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
          onChange={(event) => onChange(event.target.value)}
        />
        {error ? (
          <span className="setting-error" id={errorId} role="alert">
            {error}
          </span>
        ) : (
          <span className="setting-hint" id={hintId}>
            One item per line.
          </span>
        )}
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
        inputMode={entry.type === 'number' ? 'numeric' : undefined}
        value={String(value)}
        autoComplete={entry.secret ? 'new-password' : 'off'}
        autoCapitalize="none"
        spellCheck={false}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        ref={(node) => registerInput(entry.key, node)}
        onChange={(event) => onChange(event.target.value)}
      />
      {error && (
        <span className="setting-error" id={errorId} role="alert">
          {error}
        </span>
      )}
      {!error && entry.secret && (
        <span className="setting-hint" id={hintId}>
          Leave empty to keep the current value.
        </span>
      )}
    </div>
  );
}

export default memo(SettingsPanel);
