import { onValueCreated, onValueWritten } from 'firebase-functions/v2/database';
import { initializeApp } from 'firebase-admin/app';
import { advance, validCoordinates, validPoint } from './geofence.mjs';

initializeApp();
const uidOk = uid => /^[A-Za-z0-9_-]{1,128}$/.test(uid || '');

async function createContext(root, uid, sessionId, assignmentId, pointId, startedAt) {
  if (!uidOk(uid) || !/^[0-9a-f-]{36}$/.test(sessionId || '') || !Number.isSafeInteger(startedAt)
      || startedAt > Date.now() + 5000 || startedAt < Date.now() - 86400000) return;
  const assignment = (await root.child(`assignments/${uid}`).get()).val();
  const point = assignment?.dutyPoint;
  if (!assignmentId || assignment?.id !== assignmentId || !validPoint(point) || point.id !== pointId) return;
  const context = { uid, sessionId, assignmentId, point, startedAt,
    windowStart: Number(point.windowStart) || 0, windowEnd: Number(point.windowEnd) || 0 };
  await root.child(`dutySessions/${uid}/${sessionId}`).transaction(current => current || context);
}

const triggerRegion = process.env.FUNCTIONS_EMULATOR === 'true' ? 'us-central1' : 'asia-southeast1';

export const captureDutySession = onValueWritten({ ref: '/live/{uid}', region: triggerRegion, retry: true }, async event => {
  const uid = event.params.uid, live = event.data.after.val();
  const root = event.data.after.ref.root;
  if (!uidOk(uid) || !live || typeof live.sessionId !== 'string' || !/^[0-9a-f-]{36}$/.test(live.sessionId)) return;
  const path = `dutySessions/${uid}/${live.sessionId}`;
  const sessionRef = root.child(path);
  if (live.duty === false) {
    await sessionRef.transaction(current => current && !current.endedAt ? { ...current, endedAt: Number(live.up) || Date.now() } : undefined);
    return;
  }
  if (live.duty !== true) return;
  const assignment = (await root.child(`assignments/${uid}`).get()).val();
  await createContext(root, uid, live.sessionId, live.assignmentId, assignment?.dutyPoint?.id, live.sessionStartedAt);
});

export const captureDutyEvent = onValueCreated({ ref: '/events/{id}', region: triggerRegion, retry: true }, async event => {
  const entry = event.data.val();
  const root = event.data.ref.root;
  if (!entry || !uidOk(entry.uid) || !/^[0-9a-f-]{36}$/.test(entry.sessionId || '')) return;
  if (entry.type === 'start') await createContext(root, entry.uid, entry.sessionId, entry.assignmentId, entry.pointId, entry.startedAt);
  if (entry.type === 'stop' && entry.assignmentId && entry.pointId) {
    const sessionRef = root.child(`dutySessions/${entry.uid}/${entry.sessionId}`);
    const result = await sessionRef.transaction(current => current && !current.endedAt ? { ...current, endedAt: entry.t } : undefined);
    if (!result.snapshot.exists()) throw new Error('Start event not processed yet; retry stop');
  }
});

export const recordDutyPresence = onValueCreated({ ref: '/track/{uid}/{day}/mobile/{fixTime}', region: triggerRegion, retry: true }, async event => {
  const root = event.data.ref.root;
  const { uid, day, fixTime } = event.params;
  const record = event.data.val(), meta = record?.[4], t = Number(fixTime);
  if (!uidOk(uid) || !Array.isArray(record) || !meta || !Number.isSafeInteger(t)
      || t > Date.now() + 5000 || !/^[0-9a-f-]{36}$/.test(meta.sessionId || '') || !meta.assignmentId || !meta.pointId) return;
  const expectedDay = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(new Date(t)).replaceAll('-', '');
  if (day !== expectedDay || !validCoordinates(Number(record[0]), Number(record[1]))) return;
  const context = (await root.child(`dutySessions/${uid}/${meta.sessionId}`).get()).val();
  if (!context) throw new Error('Duty session not captured yet; retry this fix');
  if (context.uid !== uid || context.sessionId !== meta.sessionId || context.assignmentId !== meta.assignmentId
      || context.point?.id !== meta.pointId || Number(meta.startedAt) !== context.startedAt) return;
  const fix = { t, lat: record[0], lng: record[1], acc: record[2] };
  await root.child(`attendance/${uid}/${meta.sessionId}`).transaction(current =>
    advance(current, fix, context.point, context));
});

// The roster is an index only. Rules recheck the live reporting chain for every detail read.
export const updateReportingRoster = onValueWritten({ ref: '/assignments/{uid}', region: triggerRegion, retry: true }, async event => {
  const uid = event.params.uid;
  const root = event.data.after.ref.root;
  if (!uidOk(uid)) return;
  const before = event.data.before.val() || {}, after = event.data.after.val() || {};
  const previous = before.supervisorUid || '', next = after.supervisorUid || '';
  const supervisor = async id => id && uidOk(id) ? (await root.child(`assignments/${id}/supervisorUid`).get()).val() || '' : '';
  const oldParent = await supervisor(previous), newParent = await supervisor(next);
  const updates = {};
  for (const id of [previous, oldParent]) if (uidOk(id)) updates[`reportingRoster/${id}/${uid}`] = null;
  for (const id of [next, newParent]) if (uidOk(id)) updates[`reportingRoster/${id}/${uid}`] = true;
  // If an SI changes their CI, move every constable under that SI as well.
  if (previous !== next) {
    const assignments = (await root.child('assignments').get()).val() || {};
    for (const [childUid, child] of Object.entries(assignments)) {
      if (child?.supervisorUid !== uid) continue;
      if (uidOk(previous)) updates[`reportingRoster/${previous}/${childUid}`] = null;
      if (uidOk(next)) updates[`reportingRoster/${next}/${childUid}`] = true;
    }
  }
  if (Object.keys(updates).length) await root.update(updates);
});
