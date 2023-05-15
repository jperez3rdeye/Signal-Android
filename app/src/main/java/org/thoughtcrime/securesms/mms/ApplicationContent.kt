package org.thoughtcrime.securesms.mms

import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.contactshare.Contact
import org.thoughtcrime.securesms.database.ThreadTable
import org.thoughtcrime.securesms.database.documents.IdentityKeyMismatch
import org.thoughtcrime.securesms.database.documents.NetworkFailure
import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.database.model.ParentStoryId
import org.thoughtcrime.securesms.database.model.StoryType
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList
import org.thoughtcrime.securesms.database.model.databaseprotos.GiftBadge
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.recipients.Recipient

data class ApplicationContent(
  val documentTitle: String = "",
  val fileName: String = "",
  val qualifiedPath: String = "",
  val contentUploadDate: String = "",
  val md5: String = "",
  val contentType: String = "",
  val url: String = "",
  val likes: Long = 0,
  val views: Long = 0,
  val subCategoryType: String = "",
  val discriminators: String = "",
  val description: String = "",
  val metadataJson: String = "",
  val content: String = "",
  val id: String = "",
  val users: String = "",
  val status: String = "",
) : SecureRequest() {

  companion object {
    @JvmStatic
    fun newAppContent(contentType: String, contentUploadDate: String, qualifiedPath: String, documentTitle: String, fileName: String): ApplicationContent {
      return ApplicationContent(
        contentType = contentType,
        contentUploadDate = contentUploadDate,
        qualifiedPath = qualifiedPath,
        documentTitle = documentTitle,
        fileName = fileName
      )
    }
  }
}
