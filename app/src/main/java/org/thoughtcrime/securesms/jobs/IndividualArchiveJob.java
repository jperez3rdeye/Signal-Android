package org.thoughtcrime.securesms.jobs;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.AttachmentId;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.database.MessageTable;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobmanager.JsonJobData;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.mms.MessageArchivalAttachment;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingMessage;
import org.thoughtcrime.securesms.mms.PartAuthority;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.Mbaas.Archiver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.thoughtcrime.securesms.jobs.PushSendJob.enqueueCompressingAndUploadAttachmentsChains;

public class IndividualArchiveJob extends MessageArchivalJob {

  public static final String KEY = "IndividualArchiveJob";

  private static final String TAG = Log.tag(IndividualArchiveJob.class);

  private static final String KEY_MESSAGE_ID       = "message_id";

  private final long messageId;

  public IndividualArchiveJob(long messageId, @NonNull Recipient recipient, boolean hasMedia, boolean isScheduledSend) {
//  public IndividualArchiveJob(long messageId, @NonNull Recipient recipient, boolean isScheduledSend) {
    this(new Parameters.Builder()
             .setQueue(isScheduledSend ? recipient.getId().toScheduledSendQueueKey() : recipient.getId().toQueueKey(false))
             .addConstraint(NetworkConstraint.KEY)
             .setLifespan(TimeUnit.DAYS.toMillis(1))
             .setMaxAttempts(Parameters.UNLIMITED)
             .build(),
         messageId);
  }

  private IndividualArchiveJob(Job.Parameters parameters, long messageId) {
    super(parameters);
    this.messageId = messageId;
  }

  public static Job create(long messageId, @NonNull Recipient recipient, boolean hasMedia, boolean isScheduledSend) {
//  public static Job create(long messageId, @NonNull Recipient recipient, boolean isScheduledSend) {
    return new IndividualArchiveJob(messageId, recipient, hasMedia, isScheduledSend);
  }

  @WorkerThread
  public static void enqueue(@NonNull Context context, @NonNull JobManager jobManager, long messageId, @NonNull Recipient recipient, boolean isScheduledSend) {
    try {
      OutgoingMessage message = SignalDatabase.messages().getOutgoingMessage(messageId);
      if (message.getScheduledDate() != -1) {
        ApplicationDependencies.getScheduledMessageManager().scheduleIfNecessary();
        return;
      }

      Set<String> attachmentUploadIds = enqueueCompressingAndUploadAttachmentsChains(jobManager, message);
      boolean     hasMedia            = attachmentUploadIds.size() > 0;
      boolean     addHardDependencies = !isScheduledSend;

      jobManager.add(IndividualArchiveJob.create(messageId, recipient, hasMedia, isScheduledSend),
                     addHardDependencies ? recipient.getId().toQueueKey() : null);
    } catch (NoSuchMessageException | MmsException e) {
      Log.w(TAG, "Failed to enqueue message.", e);
//      SignalDatabase.messages().markAsSentFailed(messageId);
//      notifyMediaMessageDeliveryFailed(context, messageId);
    }
  }

  @Nullable @Override public byte[] serialize() {
    return new JsonJobData.Builder().putLong(KEY_MESSAGE_ID, messageId).serialize();
  }

  @NonNull @Override public String getFactoryKey() {
    return KEY;
  }

  @Override protected void onMessageArchive() throws Exception {
    MessageTable           database    = SignalDatabase.messages();
    OutgoingMessage        message     = database.getOutgoingMessage(messageId);
    final List<Attachment> attachments = message.getAttachments();

    String       senderPhoneNumber = Recipient.self().getE164().isPresent() ? Recipient.self().getE164().get() : "";
    String       toPhoneNumber     = message.getThreadRecipient().getE164().isPresent() ? message.getThreadRecipient().getE164().get() : "";
    List<String> to                = new ArrayList<>();
    to.add(toPhoneNumber);

    List<MessageArchivalAttachment> messageArchivalAttachments = new ArrayList<>();
    Archiver                        archiver                   = new Archiver();
    for (Attachment attachment : attachments) {
      DatabaseAttachment databaseAttachment = (DatabaseAttachment) attachment;
      Uri                publicUri          = databaseAttachment.getPublicUri();
      String             generatedFileName  = generateFilenameHash(messageId, message.getSentTimeMillis(), databaseAttachment.getAttachmentId());
      File               tempFile;
      InputStream        partAuthority      = null;
      if (publicUri != null) {
        partAuthority = PartAuthority.getAttachmentStream(ApplicationDependencies.getApplication().getApplicationContext(), publicUri);
      }

      try {
        final String[] contentType = databaseAttachment.getContentType().split("/");
        tempFile = File.createTempFile(generatedFileName, "." + contentType[1]);
        messageArchivalAttachments.add(new MessageArchivalAttachment(tempFile.getName(), "", databaseAttachment.getContentType(), null));

        try (OutputStream output = new FileOutputStream(tempFile)) {
          byte[] buffer = new byte[4 * 1024]; // or other buffer size
          int    read;

          if (partAuthority != null) {
            while ((read = partAuthority.read(buffer)) != -1) {
              output.write(buffer, 0, read);
            }
          }

          output.flush();
        }

        archiver.uploadContent(tempFile, tempFile.getAbsolutePath());
//        archiver.attachmentRequestBody(databaseAttachment.getContentType(), tempFile, "https://ec2-52-36-205-206.us-west-2.compute.amazonaws.com:8545/MBAAS/324fsrest/content/messagearchival/contents");

        if (tempFile.delete()) {
          Log.i(TAG, "tempFile deleted");
        } else {
          Log.i(TAG, "tempFile not deleted");
        }

      } catch (IOException | RuntimeException e) {
        e.printStackTrace();
        throw new RuntimeException(e);
      }
    }
    archiver.messageRequestBody(senderPhoneNumber, to, message.getBody(), String.valueOf(message.getSentTimeMillis()), messageArchivalAttachments);
  }

  private String generateFilenameHash(Long messageId, Long sentTimestamp, AttachmentId fileName) {

    String updatedFileName = "";

    // this next block of code generates a hash and adds to the filename
    // to solve similar filename collisions
    String toHash        = fileName.getUniqueId() + String.valueOf(sentTimestamp) + messageId;
    String newHashString = "";

    try {
      // Static getInstance method is called with hashing MD5
      MessageDigest md;
      md = MessageDigest.getInstance("MD5");
      byte[] hash = md.digest(toHash.getBytes(StandardCharsets.UTF_8));
      // Convert byte array into hexadecimal value that is 16 bytes long (32 chars)
      BigInteger number = new BigInteger(1, hash);
      newHashString = number.toString(16);
    } catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
      Log.e(TAG, "Error occurred during MD5 MessageDigest hashing");
    }

    if (updatedFileName.contains(".")) {
      String extension       = updatedFileName.substring(updatedFileName.lastIndexOf('.'));
      String filenameSansExt = updatedFileName.substring(0, updatedFileName.lastIndexOf('.'));
      updatedFileName = filenameSansExt + "_" + newHashString + extension;
    } else {
      updatedFileName = updatedFileName + "_" + newHashString;
    }

    updatedFileName = updatedFileName.replace(" ", "_").replace(":", "");

    Log.i(TAG, "New updatedFileName after hashing: " + updatedFileName);

    return updatedFileName;
  }

  public static long getMessageId(@Nullable byte[] serializedData) {
    JsonJobData data = JsonJobData.deserialize(serializedData);
    return data.getLong(KEY_MESSAGE_ID);
  }

  public static final class Factory implements Job.Factory<IndividualArchiveJob> {
    @Override
    public @NonNull IndividualArchiveJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      JsonJobData data = JsonJobData.deserialize(serializedData);
      return new IndividualArchiveJob(parameters, data.getLong(KEY_MESSAGE_ID));
    }
  }
}
