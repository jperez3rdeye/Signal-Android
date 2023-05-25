/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util.Mbaas;

import com.a324.mbaaslibrary.manager.impl.ContentManager;
import com.a324.mbaaslibrary.model.DocumentInfo;
import com.a324.mbaaslibrary.service.SecuredConnectionService;
import com.a324.mbaaslibrary.util.AsyncStringResult;
import com.a324.mbaaslibrary.util.MD5;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.mms.MessageArchival;
import org.thoughtcrime.securesms.mms.MessageArchivalAttachment;

import java.io.File;
import java.io.IOException;
import java.util.List;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Archiver {
  private static final String TAG = Log.tag(Archiver.class);
  OkHttpClient mbaasOkHttpClient = ApplicationDependencies.getMbaasOkHttpClient();

  public void attachmentRequestBody(String contentType, File file, String url) {
    final okhttp3.MediaType MEDIA_TYPE = okhttp3.MediaType.parse(contentType);

    RequestBody requestBody = new MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("file", file.getName(), RequestBody.create(MEDIA_TYPE, file))
        .addFormDataPart("name", file.getName())
        .addFormDataPart("title", file.getName())
        .addFormDataPart("discriminator", "attachment")
        .addFormDataPart("email", "jperez@t3rdeyetech.com")
        .addFormDataPart("md5", MD5.calculateMD5(file))
        .addFormDataPart("qualifier", "signal")
        .addFormDataPart("description", "")
        .build();

    createRequest(mbaasOkHttpClient, url, requestBody);
  }

  public void messageRequestBody(String senderPhoneNumber, List<String> to, String body, String sentTimeMillis, List<MessageArchivalAttachment> messageArchivalAttachments) {
    MessageArchival messageArchival = new MessageArchival("", "messagearchival", "Message", "SIGNAL", senderPhoneNumber, to, body, sentTimeMillis, messageArchivalAttachments, false, "", "Out");
    ObjectMapper om = new ObjectMapper();
    om.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    String      metaDataJsonString = null;
    try {
      metaDataJsonString = om.writeValueAsString(messageArchival);
    } catch (JsonProcessingException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
    RequestBody     requestBody     = RequestBody.create(MediaType.parse("application/json"), metaDataJsonString);

    createRequest(mbaasOkHttpClient, "https://ec2-52-36-205-206.us-west-2.compute.amazonaws.com:8545/MBAAS/324fsrest/metadata/messagearchival/metadatas/Message", requestBody);
  }

  private static void createRequest(OkHttpClient mbaasOkHttpClient, String url, RequestBody requestBody) {
    Request request = new Request.Builder()
        .url(url)
        .addHeader("Authorization", SecuredConnectionService.buildBasicAuthorizationString("jperez@t3rdeyetech.com", "admin", "0000000000", "%iQUzuu%4S6G"))
        .post(requestBody)
        .build();

    final Call call = mbaasOkHttpClient.newCall(request);
    try {
      Response response = call.execute();
      Log.e(TAG, "received: " + response.code());
    } catch (IOException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  public void uploadContent(File tempFile, String path) {
    // This section uses the MbaasLibrary, but there are currently issues with adding the full ib
    DocumentInfo documentInfo = new DocumentInfo();
    documentInfo.setFileName(tempFile.getName());
    documentInfo.setDocumentTitle(tempFile.getName());
    documentInfo.setQualifiedPath("/Binary/messagearchival/" + tempFile.getName());

    ContentManager.uploadContent(
        ApplicationDependencies.getApplication().getApplicationContext(),
        documentInfo,
        path,
        new AsyncStringResult() {
          @Override public void onResult(String s) {
            Log.i(TAG, "Attachment saved");
          }

          @Override public void onFailure(int i) {
            Log.e(TAG, "Attachment not saved " + i);
          }
        });
  }
}
