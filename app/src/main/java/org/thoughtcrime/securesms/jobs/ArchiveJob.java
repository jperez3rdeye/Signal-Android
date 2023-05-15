package org.thoughtcrime.securesms.jobs;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.jobmanager.Job;

public abstract class ArchiveJob extends BaseJob {

  private final static String TAG = Log.tag(ArchiveJob.class);

  public ArchiveJob(Job.Parameters parameters) { super(parameters); }

  @Override protected void onRun() throws Exception {
    Log.i(TAG, "Starting Message Archival attempt");
    onSend();
    Log.i(TAG, "Message Archival completed");
  }

  protected abstract void onSend() throws Exception;
}
