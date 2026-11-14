export const validCoordinates = (lat, lng) => Number.isFinite(lat) && Number.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180;

export function metres(lat, lng, pointLat, pointLng) {
  const rad = Math.PI / 180;
  const a = Math.sin((pointLat - lat) * rad / 2) ** 2
    + Math.cos(lat * rad) * Math.cos(pointLat * rad) * Math.sin((pointLng - lng) * rad / 2) ** 2;
  return 12742000 * Math.asin(Math.min(1, Math.sqrt(a)));
}

export function validPoint(point) {
  return point && typeof point.id === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(point.id)
    && validCoordinates(Number(point.lat), Number(point.lng))
    && Number.isFinite(Number(point.radiusM)) && Number(point.radiusM) >= 30 && Number(point.radiusM) <= 2000;
}

// Two fresh, accurate fixes on the same side of a 15 m boundary margin confirm a transition.
export function advance(previous, fix, point, context) {
  const state = previous || { current: 'not_arrived', confirmed: 'not_arrived', arrivalAt: null, latestFixTime: 0, transitions: {} };
  const t = Number(fix.t), lat = Number(fix.lat), lng = Number(fix.lng), acc = Number(fix.acc);
  if (!validPoint(point) || !validCoordinates(lat, lng) || !Number.isSafeInteger(t)
      || !Number.isFinite(acc) || acc < 0 || acc > 150
      || t <= Number(state.latestFixTime || 0) || t < Number(context.startedAt)
      || (context.windowStart && t < context.windowStart) || (context.windowEnd && t > context.windowEnd)
      || (context.endedAt && t > context.endedAt)) return state;
  if (validCoordinates(Number(state.lastLat), Number(state.lastLng)) && state.latestFixTime) {
    const seconds = (t - Number(state.latestFixTime)) / 1000;
    const displacement = metres(Number(state.lastLat), Number(state.lastLng), lat, lng)
      - Number(state.accuracyM || 0) - acc;
    if (seconds > 0 && displacement / seconds > 70) return state;
  }
  const next = { ...state, transitions: { ...(state.transitions || {}) }, latestFixTime: t, accuracyM: acc,
    source: 'device GPS', fixTime: t, lastLat: lat, lastLng: lng,
    assignmentId: context.assignmentId, pointId: point.id, sessionId: context.sessionId };
  if (acc > 50) { next.current = 'uncertain'; next.candidate = null; return next; }
  const distance = metres(lat, lng, Number(point.lat), Number(point.lng));
  next.distanceM = Math.round(distance);
  const target = distance + acc <= Number(point.radiusM) - 15 ? 'at'
    : distance - acc >= Number(point.radiusM) + 15 ? 'left' : null;
  if (!target) { next.current = 'uncertain'; next.candidate = null; return next; }
  if (target === next.confirmed || (target === 'left' && next.confirmed === 'not_arrived')) {
    next.current = next.confirmed; next.candidate = null; return next;
  }
  const candidate = next.candidate;
  if (!candidate || candidate.target !== target || t - candidate.t > 45000 || t <= candidate.t) {
    next.current = 'uncertain'; next.candidate = { target, t }; return next;
  }
  next.confirmed = target; next.current = target; next.candidate = null;
  if (target === 'at' && !next.arrivalAt) next.arrivalAt = t;
  const id = `${t}-${target}`;
  next.transitions[id] = { id, type: target === 'at' ? (state.arrivalAt ? 'return' : 'arrival') : 'departure',
    t, accuracyM: acc, distanceM: next.distanceM, source: 'device GPS', pointId: point.id,
    assignmentId: context.assignmentId, sessionId: context.sessionId, uid: context.uid };
  return next;
}
