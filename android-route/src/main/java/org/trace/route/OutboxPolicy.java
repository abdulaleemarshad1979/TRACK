package org.trace.route;

final class OutboxPolicy {
  private OutboxPolicy() { }

  static boolean discardLive(boolean queuedDuty, String queuedSession, boolean activeDuty, String activeSession) {
    if (!queuedDuty) return activeDuty;
    return !activeDuty || queuedSession == null || !queuedSession.equals(activeSession);
  }
}
