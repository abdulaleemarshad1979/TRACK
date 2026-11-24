package org.trace.route;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Priority;

public final class TrackService extends Service {
  static volatile boolean running;
  private static final long UPDATE_MS = 10000;
  private static final long FRESH_SPEED_MS = 45000;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final AtomicBoolean uploadPending = new AtomicBoolean();
  private final Handler loop = new Handler(Looper.getMainLooper());
  private Session session;
  private Outbox outbox;
  private LocationManager locations;
  private FusedLocationProviderClient fused;
  private LocationCallback callback;
  private Location last;
  private int lastSpeedKmh;
  private LocationListener platformListener;
  private volatile boolean stopping;
  private android.os.PowerManager.WakeLock wake;
  private String ownerUid, dutyId;
  private long lastPublished;
  private long lastQueuedFix;
  private long lastLiveFix;
  private boolean sentFresh;
  private long nextRetryAt;
  private long nextDeviceRegistrationAt;
  private long nextMessagePollAt;
  private long nextAssignmentCheckAt;
  private final Runnable tick = new Runnable() { public void run() { heartbeat(); loop.postDelayed(this, UPDATE_MS); } };
  @Override public void onCreate() {
    super.onCreate(); session = new Session(this); outbox = new Outbox(this);
    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    nm.createNotificationChannel(new NotificationChannel("tracking", "Duty tracking", NotificationManager.IMPORTANCE_LOW));
    nm.createNotificationChannel(new NotificationChannel("messages", "Route messages", NotificationManager.IMPORTANCE_HIGH));
    locations = (LocationManager) getSystemService(LOCATION_SERVICE);
    if (!session.authenticated() || !session.get("duty").equals("1") || session.get("sessionId").isEmpty()
        || session.get("assignment").isEmpty() || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return; }
    ownerUid = session.uid(); dutyId = session.get("sessionId");
    startForeground(1, trackingNotification("Duty active • location sharing"));
    running = true;
    wake = ((android.os.PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "track:duty");
    wake.acquire(); // keeps the 10 s tick running with the screen off
    try {
      long t = Long.parseLong(session.get("lastFix"));
      Location previous = new Location("last known");
      previous.setLatitude(Double.parseDouble(session.get("lastLat"))); previous.setLongitude(Double.parseDouble(session.get("lastLng")));
      previous.setTime(t); previous.setAccuracy(150);
      if (DutyPoint.valid(previous.getLatitude(), previous.getLongitude())) { last = previous; lastQueuedFix = t; lastLiveFix = t; }
    } catch (Exception ignored) { }
    fused = LocationServices.getFusedLocationProviderClient(this);
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
      LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_MS)
        .setMinUpdateIntervalMillis(UPDATE_MS).setMinUpdateDistanceMeters(0).build();
      callback = new LocationCallback() { @Override public void onLocationResult(LocationResult result) { for (Location l : result.getLocations()) onLocationChanged(l); } };
      // Android's GPS provider remains usable on phones without Google Play Services.
      platformListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) { TrackService.this.onLocationChanged(l); }
        @Override public void onStatusChanged(String p, int status, android.os.Bundle extras) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { heartbeat(); }
      };
      try { locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_MS, 0, platformListener, Looper.getMainLooper()); }
      catch (Exception e) { session.set("syncError", "GPS provider unavailable"); }
      try {
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper()).addOnFailureListener(e -> session.set("gpsProvider", "Android GPS"));
        fused.getLastLocation().addOnSuccessListener(l -> { if (l != null) onLocationChanged(l); });
      } catch (Exception ignored) { /* Android GPS remains active. */ }
    }
    loop.post(tick);
  }
  private Notification trackingNotification(String text) {
    PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Intent stop = new Intent(this, MainActivity.class).setAction("org.trace.route.CONFIRM_STOP").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent confirm = PendingIntent.getActivity(this, 3, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, "tracking").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("TRACK Route")
      .setContentText(text).setContentIntent(open).addAction(android.R.drawable.ic_media_pause, "Stop Duty", confirm).setOngoing(true).build();
  }
  // A background restart cannot safely regain while-in-use location on current Android versions.
  @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_NOT_STICKY; }
  @Override public IBinder onBind(Intent intent) { return null; }
  public void onLocationChanged(Location location) {
    if (!eligible() || (last != null && location.getTime() <= last.getTime())) return;
    long now = System.currentTimeMillis();
    if (!DutyPoint.valid(location.getLatitude(), location.getLongitude())) return;
    if (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos() > 45000000000L) return;
    if (location.getTime() < now - 45000 || location.getTime() > now + 5000) return;
    if (!location.hasAccuracy() || location.getAccuracy() > 150) return;
    if (last != null && location.getAccuracy() > Math.max(50, last.getAccuracy() * 3) && now - last.getTime() < 60000) return;
    lastSpeedKmh = speedKmh(location, last);
    last = new Location(location);
    session.set("lastLat", String.valueOf(location.getLatitude())); session.set("lastLng", String.valueOf(location.getLongitude()));
    session.set("lastFix", String.valueOf(location.getTime())); session.set("lastSpeedKmh", String.valueOf(lastSpeedKmh));
    if (location.getTime() > lastQueuedFix) {
      try {
        java.text.SimpleDateFormat date = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT);
        date.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
        String day = date.format(new java.util.Date(location.getTime()));
        JSONObject assignment = new JSONObject(session.get("assignment"));
        JSONObject point = assignment.optJSONObject("dutyPoint");
        JSONArray record = new JSONArray().put(location.getLatitude()).put(location.getLongitude()).put(location.getAccuracy()).put(lastSpeedKmh);
        if (point != null && !assignment.optString("id").isEmpty()) record.put(new JSONObject().put("sessionId", dutyId)
          .put("assignmentId", assignment.optString("id")).put("pointId", point.optString("id"))
          .put("startedAt", Long.parseLong(session.get("sessionStartedAt"))));
        outbox.add("track/" + ownerUid + "/" + day + "/mobile/" + location.getTime(), record);
        lastQueuedFix = location.getTime();
      } catch (Exception e) { session.set("syncError", "Could not queue GPS point"); }
    }
    heartbeat();
  }
  private static int speedKmh(Location current, Location previous) {
    if (current.getAccuracy() <= 50 && current.hasSpeed() && (!current.hasSpeedAccuracy() || current.getSpeedAccuracyMetersPerSecond() <= 5)) {
      float metresPerSecond = current.getSpeed();
      return Float.isFinite(metresPerSecond) && metresPerSecond >= 0 && metresPerSecond <= 70
        ? Math.round(metresPerSecond * 3.6f) : 0;
    }
    if (previous == null || !previous.hasAccuracy() || previous.getAccuracy() > 50 || current.getAccuracy() > 50) return 0;
    float seconds = (current.getTime() - previous.getTime()) / 1000f;
    if (seconds < 3 || seconds > 70) return 0;
    float distance = previous.distanceTo(current);
    float noise = Math.max(10, previous.getAccuracy() + current.getAccuracy());
    float metresPerSecond = (distance - noise) / seconds;
    return metresPerSecond > 0 && metresPerSecond <= 70 ? Math.round(metresPerSecond * 3.6f) : 0;
  }
  private boolean gpsOn() { try { return locations.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception e) { return false; } }
  private int battery() {
    Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    if (i == null) return -1; int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
    return scale > 0 ? Math.round(level * 100f / scale) : -1;
  }
  private int signal() {
    try {
      if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return -1;
      if (android.os.Build.VERSION.SDK_INT < 28) return -1;
      SignalStrength s = ((TelephonyManager) getSystemService(TELEPHONY_SERVICE)).getSignalStrength();
      return s == null ? -1 : s.getLevel();
    } catch (Exception e) { return -1; }
  }
  private void heartbeat() {
    if (!eligible()) { stopSelf(); return; }
    if (!session.configured()) return;
    final long now = System.currentTimeMillis();
    final long elapsed = SystemClock.elapsedRealtime();
    final Location l = last == null ? null : new Location(last);
    final boolean newFix = l != null && l.getTime() > lastLiveFix;
    boolean firstFix = newFix && !sentFresh;
    if (!firstFix && elapsed - lastPublished < UPDATE_MS) return;
    if (!uploadPending.compareAndSet(false, true)) return;
    lastPublished = elapsed;
    if (newFix) { lastLiveFix = l.getTime(); sentFresh = true; }
    final int speed = l != null && now - l.getTime() <= FRESH_SPEED_MS ? lastSpeedKmh : 0;
    final int bat = battery(), sig = signal(); final boolean gps = gpsOn();
    session.set("battery", String.valueOf(bat)); session.set("signal", String.valueOf(sig)); session.set("gps", gps ? "on" : "off");
    if (!gps) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, trackingNotification("GPS disabled • turn on location"));
    io.execute(() -> {
      try {
        if (!eligible()) return;
        JSONObject assignment = new JSONObject(session.get("assignment"));
        JSONObject live = new JSONObject().put("up", now).put("receivedAt", new JSONObject().put(".sv", "timestamp"))
          .put("gps", gps).put("bat", bat).put("sig", sig).put("v", BuildConfig.VERSION_NAME).put("duty", true).put("sessionId", dutyId)
          .put("sessionStartedAt", Long.parseLong(session.get("sessionStartedAt")))
          .put("ps", session.get("ps")).put("route", session.get("route")).put("sd", session.get("sd")).put("officer", session.get("officer")).put("mobile", session.get("mobile")).put("el", session.get("el"));
        if (!assignment.optString("id").isEmpty()) live.put("assignmentId", assignment.optString("id"));
        if (l != null) {
          live.put("lat", l.getLatitude()).put("lng", l.getLongitude()).put("t", l.getTime()).put("acc", l.getAccuracy()).put("spd", speed).put("hdg", l.getBearing());
        }
        if (!eligible()) return;
        outbox.add("live/" + ownerUid, live);
        if (System.currentTimeMillis() < nextRetryAt) return;
        int queuedBefore = outbox.size();
        outbox.flush(session, 6);
        nextRetryAt = 0;
        session.set("syncError", "");
        if (outbox.size() < queuedBefore) session.set("lastSync", String.valueOf(System.currentTimeMillis()));
        if (System.currentTimeMillis() >= nextAssignmentCheckAt) {
          nextAssignmentCheckAt = System.currentTimeMillis() + 60000;
          JSONObject remote = session.dbGet("assignments/" + ownerUid);
          JSONObject saved = new JSONObject(session.get("assignment"));
          if (remote.length() == 0 || !remote.optString("ps").equals(saved.optString("ps"))
              || !remote.optString("route").equals(saved.optString("route"))
              || !remote.optString("id").equals(saved.optString("id"))
              || !samePoint(remote.optJSONObject("dutyPoint"), saved.optJSONObject("dutyPoint"))) {
            session.set("duty", "0");
            outbox.removePath("live/" + ownerUid);
            JSONObject ended = new JSONObject().put("up", System.currentTimeMillis()).put("duty", false).put("gps", false).put("sessionId", dutyId);
            if (l != null) ended.put("lat", l.getLatitude()).put("lng", l.getLongitude()).put("t", l.getTime());
            outbox.add("live/" + ownerUid, ended);
            stopSelf(); return;
          }
        }
        if (System.currentTimeMillis() >= nextDeviceRegistrationAt) {
          nextDeviceRegistrationAt = System.currentTimeMillis() + 5 * 60000;
          try {
            JSONObject device = new JSONObject().put("sd", session.get("sd")).put("ps", session.get("ps"))
              .put("route", session.get("route")).put("officer", session.get("officer")).put("mobile", session.get("mobile"))
              .put("force", "route").put("el", session.get("el")).put("v", BuildConfig.VERSION_NAME)
              .put("model", android.os.Build.MODEL);
            session.dbPut("devices/" + session.uid(), device);
            nextDeviceRegistrationAt = System.currentTimeMillis() + 60 * 60000;
          } catch (Exception ignored) { /* Live telemetry already reached the control room; retry registration later. */ }
        }
        if (System.currentTimeMillis() >= nextMessagePollAt) { nextMessagePollAt = System.currentTimeMillis() + 30000; pollMessage(); }
      } catch (Exception e) {
        nextRetryAt = System.currentTimeMillis() + 15000;
        session.set("syncError", "Location upload failed. Check connection and account assignment.");
      } finally { uploadPending.set(false); }
    });
  }
  private boolean eligible() {
    return !stopping && ownerUid != null && ownerUid.equals(session.uid()) && dutyId.equals(session.get("sessionId"))
      && session.authenticated() && session.get("duty").equals("1")
      && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
  }
  private boolean samePoint(JSONObject a, JSONObject b) {
    if (a == null || b == null) return a == b;
    return a.optString("id").equals(b.optString("id"))
      && a.optDouble("lat") == b.optDouble("lat") && a.optDouble("lng") == b.optDouble("lng")
      && a.optDouble("radiusM") == b.optDouble("radiusM")
      && a.optLong("windowStart") == b.optLong("windowStart") && a.optLong("windowEnd") == b.optLong("windowEnd");
  }
  private void pollMessage() {
    try {
      JSONObject m = session.dbGet("msg/" + session.uid()); String id = m.optString("id");
      if (id.isEmpty() || id.equals(session.get("lastMsgId"))) return;
      session.set("lastMsgId", id); session.set("lastMsg", m.optString("text")); session.set("lastVoice", m.optString("voice"));
      NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      Notification n = new Notification.Builder(this, "messages").setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("TRACK Control Room").setContentText(m.optString("text", "New message")).setContentIntent(open).setAutoCancel(true).build();
      nm.notify(id.hashCode(), n);
    } catch (Exception ignored) { }
  }
  @Override public void onDestroy() {
    stopping = true;
    running = false;
    if (locations != null && platformListener != null) locations.removeUpdates(platformListener);
    loop.removeCallbacks(tick);
    if (fused != null && callback != null) fused.removeLocationUpdates(callback);
    if (wake != null && wake.isHeld()) wake.release();
    io.shutdown(); super.onDestroy();
  }
}
