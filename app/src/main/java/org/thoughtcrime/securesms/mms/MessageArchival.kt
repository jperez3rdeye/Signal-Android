package org.thoughtcrime.securesms.mms

data class MessageArchival(
  val jsonHash: String = "",
  val appName: String = "",
  val contentType: String = "",
  val carrier: String = "",
  val from: String = "",
  val to: List<String> = emptyList(),
  val messageText: String = "",
  val timestamp: String = "",
  val attachment: List<MessageArchivalAttachment> = emptyList(),
  val isRead: Boolean = false,
  val isLogged: String = "",
  val direction: String = "Out"
)