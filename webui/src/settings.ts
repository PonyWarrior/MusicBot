import type { SettingEntry } from './api';

export type FieldValue = string | boolean;

export interface Payload {
  type: string;
  value: unknown;
}

/** The part of the key after its section, which is what a field is labelled with. */
export function fieldLabel(entry: SettingEntry): string {
  return entry.key.includes('.') ? entry.key.substring(entry.key.indexOf('.') + 1) : entry.key;
}

export function sectionOf(entry: SettingEntry): string {
  return entry.key.split('.')[0];
}

/**
 * A DOM id that is unique per key: dots become `-`, any other character is escaped as `_hex`,
 * so `a.b-c` and `a.b.c` no longer share an id.
 */
export function fieldId(entry: SettingEntry): string {
  const escaped = entry.key.replace(/[^a-zA-Z0-9]/g, (ch) =>
    ch === '.' ? '-' : `_${ch.charCodeAt(0).toString(16)}`,
  );
  return `setting-${escaped}`;
}

/** Lists are edited one item per line, so an entry may safely contain a comma. */
export function fromEntry(entry: SettingEntry, value: unknown): FieldValue {
  if (entry.type === 'boolean') return Boolean(value);
  if (entry.type === 'list') return Array.isArray(value) ? value.join('\n') : String(value ?? '');
  return String(value ?? '');
}

/**
 * What is sent for one field.
 *
 * <p>A number travels as the text that was typed. A Discord ID has 18 or 19 digits, more than
 * a JavaScript number can hold, so converting it would save a different ID.
 */
export function toPayload(entry: SettingEntry, value: FieldValue): Payload {
  if (entry.type === 'boolean') return { type: 'boolean', value: Boolean(value) };
  if (entry.type === 'number') return { type: 'number', value: String(value).trim() };
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

/** A plain decimal number, as the server accepts it. Checked as text to keep every digit. */
export function isNumberText(text: string): boolean {
  return /^[-+]?(\d+(\.\d*)?|\.\d+)$/.test(text.trim());
}

/** The fields whose value differs from the file. A secret counts once something is typed. */
export function changedKeys(
  entries: SettingEntry[],
  values: Record<string, FieldValue>,
  initial: Record<string, FieldValue>,
): string[] {
  return entries
    .filter((entry) =>
      entry.secret
        ? String(values[entry.key] ?? '').length > 0
        : values[entry.key] !== initial[entry.key],
    )
    .map((entry) => entry.key);
}

/** Whether a field is shown for what was typed into the filter box. */
export function matchesFilter(entry: SettingEntry, filter: string): boolean {
  const words = filter.toLowerCase().split(/\s+/).filter(Boolean);
  if (words.length === 0) return true;
  const text = `${entry.key} ${entry.description ?? ''}`.toLowerCase();
  return words.every((word) => text.includes(word));
}
