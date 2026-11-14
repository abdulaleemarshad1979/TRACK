package org.trace.route;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Installs a downloaded APK through PackageInstaller; Android 12+ updates need no tap once this app is the installer of record. */
final class ApkInstaller {
  private ApkInstaller() {}

  static void install(Context c, File apk) throws java.io.IOException {
    PackageInstaller pi = c.getPackageManager().getPackageInstaller();
    PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
    if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
    int id = pi.createSession(p);
    try (PackageInstaller.Session s = pi.openSession(id);
         InputStream in = new FileInputStream(apk);
         OutputStream out = s.openWrite("update", 0, apk.length())) {
      byte[] buf = new byte[65536]; int n;
      while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
      s.fsync(out);
      Intent result = new Intent(c, Result.class).setPackage(c.getPackageName());
      s.commit(PendingIntent.getBroadcast(c, id, result, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE).getIntentSender());
    }
  }

  /** Opens Android's confirm screen when it still wants a tap (first update, or Android 11 and older). */
  public static final class Result extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
      if (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) != PackageInstaller.STATUS_PENDING_USER_ACTION) return;
      Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
      if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
  }
}
