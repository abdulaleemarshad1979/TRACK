import { test } from 'node:test';
import assert from 'node:assert/strict';
import { advance } from './geofence.mjs';

const point = { id: 'cm-gate-a', lat: 17, lng: 81, radiusM: 100 };
const context = { uid: 'officer', sessionId: 'session', assignmentId: 'assignment', startedAt: 1000, windowStart: 1000, windowEnd: 200000 };
const fix = (t, lat, acc = 5) => ({ t, lat, lng: 81, acc });

test('login, unrelated points and one fix cannot mark arrival', () => {
  let state = advance(null, fix(10000, 17.02), point, context);
  assert.equal(state.confirmed, 'not_arrived');
  state = advance(state, fix(20000, 17), point, context);
  assert.equal(state.confirmed, 'not_arrived');
  assert.deepEqual(state.transitions, {});
});

test('entry, departure and return each create one transition and keep first arrival', () => {
  let state;
  for (const sample of [fix(10000, 17), fix(20000, 17), fix(30000, 17.0018), fix(40000, 17.0018), fix(50000, 17), fix(60000, 17)])
    state = advance(state, sample, point, context);
  assert.equal(state.current, 'at');
  assert.equal(state.arrivalAt, 20000);
  assert.deepEqual(Object.values(state.transitions).map(x => x.type), ['arrival', 'departure', 'return']);
  assert.equal(advance(state, fix(60000, 17), point, context), state);
});

test('implausible location jump cannot mark a departure', () => {
  let state = advance(null, fix(10000, 17), point, context);
  state = advance(state, fix(20000, 17), point, context);
  const old = state;
  state = advance(state, fix(30000, 18), point, context);
  assert.equal(state, old);
  assert.equal(state.current, 'at');
});

test('boundary noise, poor accuracy, old and out-of-window fixes cannot create transitions', () => {
  let state = advance(null, fix(10000, 17.00085, 20), point, context);
  state = advance(state, fix(20000, 17, 90), point, context);
  state = advance(state, fix(500, 17), point, context);
  state = advance(state, fix(210000, 17), point, context);
  assert.equal(state.confirmed, 'not_arrived');
  assert.deepEqual(state.transitions, {});
});
