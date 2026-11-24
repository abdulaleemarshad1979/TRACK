package org.trace.route;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/** An app update kills the process; resume tracking only when Android lets a background start keep location (needs "all the time"). */
public final class PackageReplacedReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c, Intent i) {
    Session s = new Session(c);
    // Same conditions as TrackService.onCreate, which would stopSelf() before startForeground() and crash otherwise.
    if (!s.authenticated() || !s.get("duty").equals("1") || s.get("sessionId").isEmpty() || s.get("assignment").isEmpty()
        || c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        || c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
    try { c.startForegroundService(new Intent(c, TrackService.class)); } catch (Exception ignored) { /* Resume Duty stays visible in the app. */ }
  }
}
