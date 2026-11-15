import assert from 'node:assert/strict';
import { test } from 'node:test';
import { share } from '../src/share.ts';

const state = () => ({
  bot: { name: 'JMusicBot', uptimeSeconds: 100 },
  queue: [
    { id: 'a', position: 1, title: 'One' },
    { id: 'b', position: 2, title: 'Two' },
  ],
  nowPlaying: null,
});

test('an identical poll returns the previous state itself', () => {
  const previous = state();
  assert.equal(share(previous, state()), previous);
});

test('only the part that changed gets a new identity', () => {
  const previous = state();
  const next = state();
  next.bot.uptimeSeconds = 101;

  const shared = share(previous, next);
  assert.notEqual(shared, previous);
  assert.notEqual(shared.bot, previous.bot);
  assert.equal(shared.bot.uptimeSeconds, 101);
  assert.equal(shared.queue, previous.queue);
  assert.equal(shared.queue[0], previous.queue[0]);
});

test('a changed row is replaced and its neighbours are kept', () => {
  const previous = state();
  const next = state();
  next.queue[1].title = 'Two (remastered)';

  const shared = share(previous, next);
  assert.notEqual(shared.queue, previous.queue);
  assert.equal(shared.queue[0], previous.queue[0]);
  assert.equal(shared.queue[1].title, 'Two (remastered)');
});

test('rows are matched by id when the list is reordered', () => {
  const previous = [
    { id: 'a', title: 'One' },
    { id: 'b', title: 'Two' },
  ];
  const shared = share(previous, [
    { id: 'b', title: 'Two' },
    { id: 'a', title: 'One' },
  ]);
  assert.notEqual(shared, previous);
  assert.equal(shared[0], previous[1]);
  assert.equal(shared[1], previous[0]);
});

test('a list that grew or shrank is a new list', () => {
  const previous = ['line 1', 'line 2'];
  assert.deepEqual(share(previous, ['line 1']), ['line 1']);
  assert.notEqual(share(previous, ['line 1', 'line 2', 'line 3']), previous);
  assert.equal(share(previous, ['line 1', 'line 2']), previous);
});

test('a removed or added key is a change', () => {
  const previous = { a: 1, b: 2 };
  assert.deepEqual(share(previous, { a: 1 }), { a: 1 });
  assert.deepEqual(share(previous, { a: 1, b: 2, c: 3 }), { a: 1, b: 2, c: 3 });
});

test('the first poll and a value that changes kind pass through', () => {
  const next = state();
  assert.equal(share(null, next), next);
  assert.equal(share({ title: 'Track' }, null), null);
  assert.deepEqual(share([1], { id: 'x' }), { id: 'x' });
});
