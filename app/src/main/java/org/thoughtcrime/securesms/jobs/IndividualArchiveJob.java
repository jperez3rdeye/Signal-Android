package org.thoughtcrime.securesms.jobs;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.a324.mbaaslibrary.manager.impl.ContentManager;
import com.a324.mbaaslibrary.service.SecuredConnectionService;
import com.a324.mbaaslibrary.util.AsyncStringResult;
import com.a324.mbaaslibrary.util.MD5;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.database.MessageTable;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobmanager.JsonJobData;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.mms.ApplicationContent;
import org.thoughtcrime.securesms.mms.MessageArchival;
import org.thoughtcrime.securesms.mms.MessageArchivalAttachment;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingMessage;
import org.thoughtcrime.securesms.recipients.Recipient;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import khandroid.ext.apache.http.entity.mime.HttpMultipartMode;
import khandroid.ext.apache.http.entity.mime.MultipartEntity;
import khandroid.ext.apache.http.entity.mime.content.FileBody;
import khandroid.ext.apache.http.entity.mime.content.StringBody;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static org.thoughtcrime.securesms.jobs.PushSendJob.enqueueCompressingAndUploadAttachmentsChains;

public class IndividualArchiveJob extends MessageArchivalJob {

  public static final String KEY = "IndividualArchiveJob";

  private static final String TAG = Log.tag(IndividualArchiveJob.class);

  private static final String KEY_MESSAGE_ID = "message_id";

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
    MessageTable    database          = SignalDatabase.messages();
    OutgoingMessage message           = database.getOutgoingMessage(messageId);
    OkHttpClient    mbaasOkHttpClient = ApplicationDependencies.getMbaasOkHttpClient();

    String       senderPhoneNumber = Recipient.self().getE164().isPresent() ? Recipient.self().getE164().get() : "";
    String       toPhoneNumber     = message.getThreadRecipient().getE164().isPresent() ? message.getThreadRecipient().getE164().get() : "";
    List<String> to                = new ArrayList<>();
    to.add(toPhoneNumber);

    List<MessageArchivalAttachment> messageArchivalAttachments = new ArrayList<>();
    File                            file                       = null;
    String                          md5                        = "";
    String                          updatedFileName            = "";
    List<DatabaseAttachment>        attachmentsForMessage      = SignalDatabase.attachments().getAttachmentsForMessage(messageId);
    if (message.getAttachments().size() > 0) {
      for (DatabaseAttachment attachment : attachmentsForMessage) {
        DatabaseAttachment att = SignalDatabase.attachments().getAttachment(attachment.getAttachmentId());
        Uri uri  = att.getPublicUri();
        String test = att.getPublicUri().getPath();

        updatedFileName = generateFilenameHash(messageId, message.getSentTimeMillis(), "");

//        if (attachment.getLocation() != null) {
          file = new File(String.valueOf(uri));
//        }
//        byte[] data = new byte[0];
//        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
//          data = Base64.getDecoder().decode(attachment.getDigest());
//        }
//        InputStream targetStream = new ByteArrayInputStream(data);
        MessageArchivalAttachment messageArchivalAttachment = new MessageArchivalAttachment(
            updatedFileName,
            "",
            "",
            Math.toIntExact(attachment.getSize())
        );
        messageArchivalAttachments.add(messageArchivalAttachment);
//        ApplicationContent                       appContent   = buildApplicationContent(updatedFileName, messageArchivalAttachment);
        com.a324.mbaaslibrary.model.DocumentInfo documentInfo = new com.a324.mbaaslibrary.model.DocumentInfo();
        documentInfo.setFileName(updatedFileName);
        documentInfo.setDocumentTitle(updatedFileName);
        documentInfo.setQualifiedPath("/Binary/messagearchival/" + updatedFileName);

//        ContentManager.uploadContent(
//            context,
//            documentInfo,
//            attachment.getLocation(),
//            new AsyncStringResult() {
//              @Override public void onResult(String s) {
//                Log.i(TAG, "Attachment saved");
//              }
//
//              @Override public void onFailure(int i) {
//                Log.e(TAG, "Attachment not saved " + i);
//              }
//            });

        md5 = MD5.calculateMD5(file);
        RequestBody requestBody = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("fileName", updatedFileName)
            .addFormDataPart("title", updatedFileName)
            .addFormDataPart("email", "jperez@t3rdeyetech.com")
//            .addFormDataPart("md5", md5)
            .addFormDataPart("file", updatedFileName, RequestBody.create(MediaType.parse("image/jpeg"), file))
            .build();

        Request request = new Request.Builder()
            .url("https://ec2-52-36-205-206.us-west-2.compute.amazonaws.com:8545/MBAAS/324fsrest/content/messagearchival/contents")
            .addHeader("Authorization", SecuredConnectionService.buildBasicAuthorizationString("jperez@t3rdeyetech.com", "admin", "0000000000", "%iQUzuu%4S6G"))
            .post(requestBody)
            .build();

        Call call = mbaasOkHttpClient.newCall(request);

        try {
          Response response = call.execute();
          Log.d(TAG, "image save: " + response.code());
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    }

    MessageArchival messageArchival = new MessageArchival("", "messagearchival", "Message", "SIGNAL", senderPhoneNumber, to, message.getBody(), String.valueOf(message.getSentTimeMillis()), messageArchivalAttachments, false, "", "Out");

    ObjectMapper om = new ObjectMapper();
    om.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    String      metaDataJsonString = om.writeValueAsString(messageArchival);
    RequestBody requestBody        = RequestBody.create(MediaType.parse("application/json"), metaDataJsonString);
    Request request = new Request.Builder()
        .url("https://ec2-52-36-205-206.us-west-2.compute.amazonaws.com:8545/MBAAS/324fsrest/metadata/messagearchival/metadatas/Message")
        .addHeader("Authorization", SecuredConnectionService.buildBasicAuthorizationString("jperez@t3rdeyetech.com", "admin", "0000000000", "%iQUzuu%4S6G"))
        .post(requestBody)
        .build();

    Call call = mbaasOkHttpClient.newCall(request);

    try {
      Response response = call.execute();
      Log.d(TAG, "send: " + response.code());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private ApplicationContent buildApplicationContent(String updatedFileName, MessageArchivalAttachment attachment) {
    return ApplicationContent.newAppContent(
        attachment.getContentType(),
        "",
        "/Binary/" + "messagearchival" + "/" + updatedFileName,
        updatedFileName,
        updatedFileName
    );
  }

  private String generateFilenameHash(Long messageId, Long sentTimestamp, String fileName) {

    String updatedFileName = fileName.contains("\\")
                             ? fileName.substring(fileName.lastIndexOf("\\"))
                             : fileName;

    // this next block of code generates a hash and adds to the filename
    // to solve similar filename collisions
    String toHash        = String.valueOf(sentTimestamp) + messageId;
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
