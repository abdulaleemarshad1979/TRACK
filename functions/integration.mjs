import assert from 'node:assert/strict';
import { initializeApp } from 'firebase-admin/app';
import { getDatabase } from 'firebase-admin/database';

if (!process.env.FIREBASE_DATABASE_EMULATOR_HOST) throw new Error('Run against the Firebase Database and Functions emulators');
initializeApp({ projectId: 'demo-trace', databaseURL: 'https://demo-trace-default-rtdb.firebaseio.com' });
const db = getDatabase();
const waitFor = async check => {
  for (let attempt = 0; attempt < 80; attempt++) {
    const result = await check();
    if (result) return result;
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  throw new Error('Timed out waiting for function output');
};

const uid = 'field-a', sessionId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const now = Date.now(), startedAt = now - 120000;
const point = { id: 'gate-a', name: 'North Gate', eventId: 'cm-event', sectorId: 'north', lat: 17, lng: 81, radiusM: 100,
  windowStart: now - 180000, windowEnd: now + 180000 };
await db.ref().set({ roles: { [uid]: 'route', si1: 'si_monitor', si2: 'si_monitor' }, assignments: {
  [uid]: { id: 'assign-a', officer: 'Constable A', ps: 'North PS', route: 'Route 1', supervisorUid: 'si1', dutyPoint: point },
} });
await db.ref(`live/${uid}`).set({ duty: true, up: now - 90000, sessionId, assignmentId: 'assign-a', sessionStartedAt: startedAt });
await waitFor(async () => (await db.ref(`dutySessions/${uid}/${sessionId}`).get()).exists());
await waitFor(async () => (await db.ref(`reportingRoster/si1/${uid}`).get()).val() === true);

const times = [now - 80000, now - 70000, now - 60000, now - 50000, now - 40000, now - 30000];
const latitudes = [17, 17, 17.0018, 17.0018, 17, 17];
for (let i = 0; i < times.length; i++) {
  const t = times[i], day = new Date(t + 330 * 60000).toISOString().slice(0, 10).replaceAll('-', '');
  await db.ref(`track/${uid}/${day}/mobile/${t}`).set([latitudes[i], 81, 5, 0,
    { sessionId, assignmentId: 'assign-a', pointId: 'gate-a', startedAt }]);
  await waitFor(async () => (await db.ref(`attendance/${uid}/${sessionId}/latestFixTime`).get()).val() === t);
}
const presence = (await db.ref(`attendance/${uid}/${sessionId}`).get()).val();
assert.equal(presence.current, 'at');
assert.equal(presence.arrivalAt, times[1]);
assert.deepEqual(Object.values(presence.transitions).map(event => event.type), ['arrival', 'departure', 'return']);

await db.ref(`assignments/${uid}/supervisorUid`).set('si2');
await waitFor(async () => (await db.ref(`reportingRoster/si2/${uid}`).get()).val() === true);
await waitFor(async () => !(await db.ref(`reportingRoster/si1/${uid}`).get()).exists());
console.log('Emulator duty trip and reporting reassignment passed');
process.exit(0);
