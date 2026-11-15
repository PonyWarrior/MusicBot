import assert from 'node:assert/strict';
import { test } from 'node:test';
import { formatLength, formatTime, formatUptime, formatVersion } from '../src/format.ts';

test('formatTime shows minutes and seconds, and hours when there are any', () => {
  assert.equal(formatTime(0), '0:00');
  assert.equal(formatTime(59_999), '0:59');
  assert.equal(formatTime(200_000), '3:20');
  assert.equal(formatTime(3_600_000), '1:00:00');
  assert.equal(formatTime(3_725_000), '1:02:05');
});

test('formatTime does not print nonsense for a length that is not one', () => {
  assert.equal(formatTime(-1), '0:00');
  assert.equal(formatTime(Number.NaN), '0:00');
  assert.equal(formatTime(Number.POSITIVE_INFINITY), '0:00');
});

test('a stream has no length', () => {
  assert.equal(formatLength(0, true), 'LIVE');
  assert.equal(formatLength(180_000, false), '3:00');
  assert.equal(formatLength(180_000, undefined), '3:00');
});

test('formatUptime keeps the two largest units', () => {
  assert.equal(formatUptime(59), '0m');
  assert.equal(formatUptime(3_599), '59m');
  assert.equal(formatUptime(3_600), '1h 0m');
  assert.equal(formatUptime(90_000), '1d 1h');
});

test('formatVersion names a build that has no version', () => {
  assert.equal(formatVersion('0.8.0'), 'v0.8.0');
  assert.equal(formatVersion('UNKNOWN'), 'development build');
  assert.equal(formatVersion(undefined), '');
});
