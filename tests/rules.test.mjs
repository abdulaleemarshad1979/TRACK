import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { get, ref, set, update, query, orderByChild, equalTo, limitToLast } from 'firebase/database';

test('Realtime Database grants only approved roles and route ownership', async () => {
  const env = await initializeTestEnvironment({
    projectId: 'demo-trace',
    database: { rules: readFileSync(new URL('../firebase/database.rules.json', import.meta.url), 'utf8') },
  });
  try {
    await env.clearDatabase();
    await env.withSecurityRulesDisabled(async ctx => {
      await set(ref(ctx.database(), '/'), {
        roles: { admin: 'admin', monitor: 'monitor', routeA: 'route', routeB: 'route', stranger: 'route' },
        assignments: { routeA: { ps: 'PS-1', route: 'R-1' }, routeB: { ps: 'PS-2', route: 'R-2' } },
        mandals: { 'Kadiyam': { routes: 5 } },
        routes: { legacyRoute: { ps: 'PS-1' } },
        officers: { routeA: { name: 'Officer A' } },
        voice: { privateA: { uid: 'routeA', a: 'audio' }, privateB: { uid: 'routeB', a: 'audio' } },
      });
    });
    const anon = env.unauthenticatedContext().database();
    const admin = env.authenticatedContext('admin').database();
    const monitor = env.authenticatedContext('monitor').database();
    const routeA = env.authenticatedContext('routeA').database();
    const routeB = env.authenticatedContext('routeB').database();
    const stranger = env.authenticatedContext('stranger').database();

    await assertFails(get(ref(anon, 'live/routeA')));
    await assertFails(set(ref(anon, 'live/routeA'), { lat: 1 }));
    await assertSucceeds(get(ref(admin, 'live')));
    await assertSucceeds(get(ref(monitor, 'live')));
    await assertSucceeds(get(ref(admin, 'roles')));
    await assertSucceeds(get(ref(admin, 'assignments')));
    await assertSucceeds(get(ref(monitor, 'mandals')));
    await assertSucceeds(get(ref(routeA, 'routes')));
    await assertSucceeds(get(ref(routeA, 'officers/routeA')));
    await assertFails(get(ref(routeA, 'officers/routeB')));
    await assertFails(get(ref(routeA, 'live/routeB')));
    await assertSucceeds(set(ref(routeA, 'live/routeA'), { lat: 1, lng: 2, duty: true }));
    await assertFails(set(ref(routeA, 'live/routeB'), { lat: 1 }));
    await assertFails(set(ref(stranger, 'live/stranger'), { lat: 1 }));
    await assertSucceeds(set(ref(routeA, 'track/routeA/20261102/mobile/1'), [1, 2, 3, 4]));
    await assertFails(set(ref(routeB, 'track/routeA/20261102/mobile/2'), [1, 2, 3, 4]));
    await assertSucceeds(set(ref(admin, 'assignments/routeA'), { ps: 'PS-1', route: 'R-1' }));
    const assignment = { 'roles/newMember': 'route', 'assignments/newMember': { ps: 'PS-3', route: 'R-3' }, 'config/routes/r3/memberUid': 'newMember' };
    await assertFails(update(ref(monitor), assignment));
    await assertFails(update(ref(routeA), assignment));
    await assertSucceeds(update(ref(admin), assignment));
    const member = env.authenticatedContext('newMember').database();
    await assertSucceeds(get(ref(member, 'assignments/newMember')));
    await assertSucceeds(set(ref(member, 'devices/newMember'), { ps: 'PS-3', route: 'R-3' }));
    await assertFails(set(ref(routeA, 'assignments/routeA'), { ps: 'PS-X', route: 'R-X' }));
    await assertSucceeds(set(ref(routeA, 'devices/routeA'), { ps: 'PS-1', route: 'R-1' }));
    await assertFails(set(ref(routeA, 'devices/routeA'), { ps: 'PS-X', route: 'R-1' }));
    await assertSucceeds(set(ref(routeA, 'events/sos-1'), { uid: 'routeA', type: 'sos', t: 1 }));
    await assertSucceeds(get(ref(routeA, 'events/sos-1')));
    await assertFails(get(ref(routeB, 'events/sos-1')));
    await assertFails(set(ref(routeB, 'events/sos-1'), { uid: 'routeB', type: 'sos', t: 2 }));
    await assertFails(set(ref(stranger, 'events/sos-2'), { uid: 'stranger', type: 'sos', t: 2 }));
    await assertSucceeds(get(ref(routeA, 'voice/privateA')));
    await assertFails(get(ref(routeA, 'voice/privateB')));
    await assertFails(get(ref(routeA, 'voice')));
    await assertSucceeds(get(ref(monitor, 'voice/privateA')));
  } finally {
    await env.cleanup();
  }
});

test('SI monitor reads only assigned route units and cannot write', async () => {
  const env = await initializeTestEnvironment({ projectId: 'demo-trace', database: { rules: readFileSync(new URL('../firebase/database.rules.json', import.meta.url), 'utf8') } });
  try {
    await env.clearDatabase();
    await env.withSecurityRulesDisabled(async ctx => set(ref(ctx.database(), '/'), {
      roles: { admin: 'admin', si: 'si_monitor', routeA: 'route', routeB: 'route' },
      siScopes: { si: { mandal: 'Kadiyam', routes: { r1: true }, stations: { s1: true } } },
      config: { routes: { r1: { ps: 'Kadiyam PS', route: 'Route 1', memberUid: 'routeA' }, r2: { ps: 'Rajanagaram PS', route: 'Route 2', memberUid: 'routeB' } }, stations: { s1: { name: 'Kadiyam booth' }, s2: { name: 'Rajanagaram booth' } } },
      assignments: { routeA: { routeId: 'r1', ps: 'Kadiyam PS', route: 'Route 1' }, routeB: { routeId: 'r2', ps: 'Rajanagaram PS', route: 'Route 2' } },
      devices: { routeA: { ps: 'Kadiyam PS' }, routeB: { ps: 'Rajanagaram PS' } },
      live: { routeA: { lat: 17, lng: 81 }, routeB: { lat: 18, lng: 82 } },
      events: { sosA: { uid: 'routeA', type: 'sos', t: 1 }, sosB: { uid: 'routeB', type: 'sos', t: 2 } }
    }));
    const si = env.authenticatedContext('si').database();
    await assertSucceeds(get(ref(si, 'siScopes/si')));
    await assertFails(get(ref(si, 'siScopes')));
    await assertSucceeds(get(ref(si, 'config/routes/r1')));
    await assertFails(get(ref(si, 'config/routes/r2')));
    await assertSucceeds(get(ref(si, 'config/stations/s1')));
    await assertFails(get(ref(si, 'config/stations/s2')));
    await assertFails(get(ref(si, 'config/stations')));
    await assertFails(get(ref(si, 'config')));
    await assertFails(get(ref(si, 'mandals')));
    await assertFails(get(ref(si, 'routes')));
    await assertSucceeds(get(ref(si, 'devices/routeA')));
    await assertFails(get(ref(si, 'devices/routeB')));
    await assertFails(get(ref(si, 'devices')));
    await assertSucceeds(get(ref(si, 'live/routeA')));
    await assertFails(get(ref(si, 'live/routeB')));
    await assertSucceeds(get(query(ref(si, 'events'), orderByChild('uid'), equalTo('routeA'))));
    await assertFails(get(query(ref(si, 'events'), orderByChild('uid'), equalTo('routeB'))));
    await assertFails(get(ref(si, 'events')));
    await assertFails(set(ref(si, 'live/routeA'), { lat: 0 }));
    const position = { lat: 17.01, lng: 81.8, acc: 15, t: Date.now() };
    await assertSucceeds(set(ref(si, 'viewerLive/si'), position));
    await assertSucceeds(get(ref(si, 'viewerLive/si')));
    await assertFails(get(ref(si, 'viewerLive')));
    await assertFails(set(ref(si, 'viewerLive/routeA'), position));
    await assertFails(set(ref(si, 'viewerLive/si'), { ...position, lat: 181 }));
    await assertFails(set(ref(si, 'viewerLive/si'), { ...position, uid: 'routeA' }));
    await assertFails(set(ref(env.authenticatedContext('routeA').database(), 'viewerLive/routeA'), position));
    await assertFails(update(ref(si), { 'roles/routeB': 'si_monitor' }));
    const admin = env.authenticatedContext('admin').database();
    await assertSucceeds(get(ref(admin, 'viewerLive')));
    await assertSucceeds(set(ref(si, 'viewerLive/si'), null));
    await assertSucceeds(update(ref(admin), { 'roles/si2': 'si_monitor', 'siScopes/si2': { mandal: 'Kadiyam', routes: { r1: true } } }));
  } finally { await env.cleanup(); }
});

test('The viewer SOS check with a result limit stays within assigned routes', async () => {
  const env = await initializeTestEnvironment({
    projectId: 'demo-trace',
    database: { rules: readFileSync(new URL('../firebase/database.rules.json', import.meta.url), 'utf8') },
  });
  try {
    await env.clearDatabase();
    await env.withSecurityRulesDisabled(async ctx => {
      await set(ref(ctx.database(), '/'), {
        roles: { routeA: 'route', routeB: 'route', viewer: 'si_monitor' },
        assignments: { routeA: { ps: 'PS-1', route: 'R-1', routeId: 'r1' }, routeB: { ps: 'PS-2', route: 'R-2', routeId: 'r2' } },
        siScopes: { viewer: { mandal: 'Kadiyam', routes: { r1: true } } },
        events: { e1: { uid: 'routeA', type: 'start', t: 1 }, e2: { uid: 'routeA', type: 'sos', t: 2 }, e3: { uid: 'routeB', type: 'sos', t: 3 } },
      });
    });
    const viewer = env.authenticatedContext('viewer').database();
    const allowed = await assertSucceeds(get(query(ref(viewer, 'events'), orderByChild('uid'), equalTo('routeA'), limitToLast(15))));
    assert.deepEqual(Object.keys(allowed.val()).sort(), ['e1', 'e2']);
    await assertFails(get(query(ref(viewer, 'events'), orderByChild('uid'), equalTo('routeB'), limitToLast(15))));
    await assertFails(get(query(ref(viewer, 'events'), limitToLast(15))));
  } finally { await env.cleanup(); }
});

test('event reporting follows authenticated supervisor IDs and revokes old access', async () => {
  const env = await initializeTestEnvironment({ projectId: 'demo-trace', database: { rules: readFileSync(new URL('../firebase/database.rules.json', import.meta.url), 'utf8') } });
  try {
    await env.clearDatabase();
    const point = id => ({ id, name: id, lat: 17, lng: 81, radiusM: 100, eventId: 'cm', sectorId: 'north' });
    await env.withSecurityRulesDisabled(async ctx => set(ref(ctx.database(), '/'), {
      roles: { admin: 'admin', si1: 'si_monitor', si2: 'si_monitor', ci1: 'si_monitor', ci2: 'si_monitor', a: 'route', b: 'route' },
      siScopes: {
        si1: { events: { cm: true }, sectors: { north: true }, routes: { r1: true } },
        si2: { events: { cm: true }, sectors: { north: true }, routes: { r1: true } },
        ci1: { events: { cm: true }, sectors: { north: true } },
        ci2: { events: { cm: true }, sectors: { north: true } },
      },
      assignments: {
        si1: { id: 'si1a', supervisorUid: 'ci1', dutyPoint: point('si-spot') },
        si2: { id: 'si2a', supervisorUid: 'ci2', dutyPoint: point('si-spot-2') },
        a: { id: 'a1', ps: 'PS', route: 'R1', routeId: 'r1', supervisorUid: 'si1', dutyPoint: point('point-a') },
        b: { id: 'b1', ps: 'PS', route: 'R1', routeId: 'r1', supervisorUid: 'si2', dutyPoint: point('point-b') },
      },
      config: { routes: { r1: { ps: 'PS', route: 'R1', memberUid: 'a' } } },
      live: { a: { lat: 17, lng: 81, t: Date.now(), up: Date.now(), duty: true }, b: { lat: 17, lng: 81, t: Date.now(), up: Date.now(), duty: true } },
      attendance: { a: { s1: { current: 'at' } }, b: { s2: { current: 'left' } } },
      reportingRoster: { si1: { a: true }, si2: { b: true }, ci1: { si1: true, a: true }, ci2: { si2: true, b: true } },
    }));
    const si1 = env.authenticatedContext('si1').database(), si2 = env.authenticatedContext('si2').database();
    const ci1 = env.authenticatedContext('ci1').database(), ci2 = env.authenticatedContext('ci2').database();
    const a = env.authenticatedContext('a').database(), admin = env.authenticatedContext('admin').database();
    await assertSucceeds(get(ref(a, 'assignments/a')));
    await assertFails(get(ref(a, 'assignments/b')));
    await assertSucceeds(get(ref(si1, 'reportingRoster/si1')));
    await assertFails(get(ref(si1, 'reportingRoster/si2')));
    await assertSucceeds(get(ref(si1, 'assignments/a')));
    await assertSucceeds(get(ref(si1, 'live/a')));
    await assertSucceeds(get(ref(si1, 'attendance/a')));
    await assertFails(get(ref(si1, 'assignments/b')));
    await assertFails(get(ref(si1, 'live/b')));
    await assertSucceeds(get(ref(ci1, 'assignments/a')));
    await assertSucceeds(get(ref(ci1, 'attendance/a')));
    await assertFails(get(ref(ci1, 'attendance/b')));
    await assertSucceeds(get(ref(admin, 'attendance')));
    await assertFails(set(ref(a, 'attendance/a/s1'), { current: 'at' }));
    await assertFails(set(ref(a, 'events/forged-arrival'), { type: 'arrival', uid: 'a', t: Date.now() }));
    await assertFails(set(ref(a, 'track/a/20261114/mobile/1'), [91, 81, 5, 0]));
    await assertSucceeds(update(ref(admin), { 'assignments/a/supervisorUid': 'si2' }));
    await assertFails(get(ref(si1, 'live/a')));
    await assertFails(get(ref(ci1, 'attendance/a')));
    await assertSucceeds(get(ref(si2, 'live/a')));
    await assertSucceeds(get(ref(ci2, 'attendance/a')));
  } finally { await env.cleanup(); }
});
