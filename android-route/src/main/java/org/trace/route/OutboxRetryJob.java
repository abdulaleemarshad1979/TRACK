package org.trace.route;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Sends queued duty-end status and history without restarting GPS capture. */
public final class OutboxRetryJob extends JobService {
  @Override public boolean onStartJob(JobParameters params) {
    new Thread(() -> {
      Session session = new Session(this);
      if (!session.authenticated()) { jobFinished(params, false); return; }
      Outbox outbox = new Outbox(this);
      int before = outbox.size();
      try {
        outbox.flush(session, 6);
        int after = outbox.size();
        jobFinished(params, after > 0 && after < before);
      } catch (Exception e) { jobFinished(params, true); }
    }, "route-outbox-retry").start();
    return true;
  }
  @Override public boolean onStopJob(JobParameters params) { return true; }
}
