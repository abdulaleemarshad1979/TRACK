package org.trace.route;

import org.junit.Test;
import static org.junit.Assert.*;

public class OutboxPolicyTest {
  @Test public void offlineLiveCannotRestoreStoppedOrReplacedDuty() {
    assertFalse(OutboxPolicy.discardLive(true, "session-a", true, "session-a"));
    assertTrue(OutboxPolicy.discardLive(true, "session-a", false, ""));
    assertTrue(OutboxPolicy.discardLive(true, "session-a", true, "session-b"));
    assertTrue(OutboxPolicy.discardLive(false, "session-a", true, "session-b"));
    assertFalse(OutboxPolicy.discardLive(false, "session-a", false, ""));
  }
}
