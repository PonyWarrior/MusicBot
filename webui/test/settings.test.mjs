import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  changedKeys,
  fieldId,
  fieldLabel,
  fromEntry,
  isNumberText,
  matchesFilter,
  sectionOf,
  toPayload,
} from '../src/settings.ts';

const entry = (key, type, extra = {}) => ({ key, type, value: '', ...extra });

test('a Discord ID is sent with every digit', () => {
  const owner = entry('discord.owner', 'number');
  // 123456789012345678 is not representable as a JavaScript number; it rounds to ...680.
  assert.deepEqual(toPayload(owner, ' 123456789012345678 '), {
    type: 'number',
    value: '123456789012345678',
  });
  assert.equal(fromEntry(owner, '123456789012345678'), '123456789012345678');
});

test('a list is one item per line, so an item may contain a comma', () => {
  const args = entry('library.ytdlpArgs', 'list');
  assert.equal(fromEntry(args, ['--format', 'a,b']), '--format\na,b');
  assert.deepEqual(toPayload(args, ' --format \n\na,b\n'), {
    type: 'list',
    value: ['--format', 'a,b'],
  });
});

test('booleans and text keep their kind', () => {
  assert.deepEqual(toPayload(entry('library.enabled', 'boolean'), true), {
    type: 'boolean',
    value: true,
  });
  assert.deepEqual(toPayload(entry('commands.prefix', 'string'), ' $ '), {
    type: 'string',
    value: ' $ ',
  });
  assert.equal(fromEntry(entry('library.enabled', 'boolean'), false), false);
});

test('isNumberText accepts plain decimals only', () => {
  for (const text of ['0', '42', '-1', '+7', '0.55', '.5', '5.', ' 8001 ', '123456789012345678'])
    assert.equal(isNumberText(text), true, text);
  for (const text of ['', ' ', 'abc', '1e3', 'NaN', 'Infinity', '1d', '1,5', '0x10', '1 2'])
    assert.equal(isNumberText(text), false, text);
});

test('changedKeys lists what differs from the file', () => {
  const entries = [
    entry('commands.prefix', 'string'),
    entry('library.enabled', 'boolean'),
    entry('discord.token', 'string', { secret: true }),
  ];
  const initial = { 'commands.prefix': '!', 'library.enabled': true, 'discord.token': '' };

  assert.deepEqual(changedKeys(entries, { ...initial }, initial), []);
  assert.deepEqual(changedKeys(entries, { ...initial, 'library.enabled': false }, initial), [
    'library.enabled',
  ]);
  // The token is never sent to the page, so an empty field means "leave it alone".
  assert.deepEqual(changedKeys(entries, { ...initial, 'discord.token': 'new' }, initial), [
    'discord.token',
  ]);
});

test('the filter needs every word, in the key or the description', () => {
  const cipher = entry('playback.youtube.localCipher.port', 'number', {
    description: 'Port the local cipher service listens on',
  });
  assert.equal(matchesFilter(cipher, ''), true);
  assert.equal(matchesFilter(cipher, '  '), true);
  assert.equal(matchesFilter(cipher, 'CIPHER port'), true);
  assert.equal(matchesFilter(cipher, 'listens'), true);
  assert.equal(matchesFilter(cipher, 'cipher volume'), false);
  assert.equal(matchesFilter(entry('gui.mode', 'string'), 'mode'), true);
});

test('labels, sections and ids come from the key', () => {
  const key = entry('playback.youtube.localCipher.env.DENO_DIR', 'string');
  assert.equal(sectionOf(key), 'playback');
  assert.equal(fieldLabel(key), 'youtube.localCipher.env.DENO_DIR');
  assert.equal(fieldId(key), 'setting-playback-youtube-localCipher-env-DENO_5fDIR');
  assert.equal(fieldLabel(entry('standalone', 'string')), 'standalone');
});

test('ids do not collide for keys that differ only in punctuation', () => {
  const ids = ['a.b.c', 'a.b-c', 'a.b_c', 'a.b"c'].map((key) => fieldId(entry(key, 'string')));
  assert.equal(new Set(ids).size, ids.length);
});
