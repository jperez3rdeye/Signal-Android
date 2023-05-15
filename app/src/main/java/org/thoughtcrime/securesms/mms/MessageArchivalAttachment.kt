package org.thoughtcrime.securesms.mms

data class MessageArchivalAttachment(
  val name: String = "",
  val content: String = "",
  val contentType: String = "",
  val attachmentSize: Int = 0
)