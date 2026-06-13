package com.example.nerlan.data

import kotlinx.serialization.Serializable

// MARK: API envelope (Channel+)

@Serializable
data class ApiResponse<T>(
  val rtnCode: String,
  val rtnMsg: String? = null,
  val data: T? = null,
  val pagination: Pagination? = null,
) {
  val success: Boolean get() = rtnCode == "0000"
}

@Serializable
data class Pagination(
  val page: Int = 1,
  val perPage: Int = 0,
  val totalPages: Int = 1,
  val totalCount: Int = 0,
)

// MARK: Shared fragments

@Serializable
data class Tag(val tagId: String, val name: String)

@Serializable
data class ImageRef(val imageRef: String? = null)

@Serializable
data class VoiceRef(val voiceRef: String? = null)

/** A downloadable file attached to an episode — typically a PDF handout (講義). */
@Serializable
data class Attachment(
  val originalName: String? = null,
  val fileType: String? = null,
  val attachmentKey: String,
) {
  val displayName: String get() = originalName ?: "附件"

  /** File extension to store/open the attachment with (defaults to pdf). */
  val fileExtension: String
    get() {
      fileType?.takeIf { it.isNotEmpty() }?.let { return it.lowercase() }
      originalName?.takeIf { it.contains(".") }?.substringAfterLast(".")
        ?.takeIf { it.isNotEmpty() }?.let { return it.lowercase() }
      return "pdf"
    }

  val isPdf: Boolean get() = fileExtension == "pdf"
  val remoteUrl: String? get() = ChannelPlusApi.fileUrl(attachmentKey)
}

@Serializable
data class LanguageTags(
  val contentLanguage: List<Tag>? = null,
  val contentLevel: List<Tag>? = null,
)

// MARK: Programs

@Serializable
data class Program(
  val programId: String,
  val name: String,
  val description: String? = null,
  val image: ImageRef? = null,
  val episodeCount: Int? = null,
  val languageTags: LanguageTags? = null,
) {
  val language: String get() = languageTags?.contentLanguage?.firstOrNull()?.name ?: "其他"
  val level: String? get() = languageTags?.contentLevel?.firstOrNull()?.name
  val coverUrl: String? get() = ChannelPlusApi.imageUrl(image?.imageRef)

  /** Description comes back as HTML; strip tags for display. */
  val descriptionText: String
    get() = (description ?: "")
      .replace(Regex("<[^>]+>"), "")
      .replace("&nbsp;", " ")
      .trim()
}

/** Programs grouped by language for the browse list. */
data class LanguageGroup(val language: String, val programs: List<Program>)

// MARK: Episodes

@Serializable
data class Episode(
  val episodeId: String,
  val title: String? = null,
  val duration: Int? = null,
  val episodeNumber: Int? = null,
  val releaseDate: String? = null,
  val voice: VoiceRef? = null,
  val image: ImageRef? = null,
  val attachments: List<Attachment>? = null,
) {
  val displayTitle: String get() = title ?: "（無標題）"
  val audioUrl: String? get() = ChannelPlusApi.audioUrl(voice?.voiceRef)

  /** "2025-03-22T00:00:00.000Z" -> "2025/03/22" */
  val releaseDateText: String
    get() = releaseDate?.take(10)?.replace("-", "/") ?: ""

  val durationText: String
    get() = duration?.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60, it % 60) } ?: ""
}

// MARK: Local records (favorites & downloads)

/**
 * A self-contained snapshot of an episode plus its program context,
 * so favorites and downloads render without re-fetching the API.
 */
@Serializable
data class EpisodeRecord(
  val id: String,           // episode id
  val title: String,
  val playDate: String? = null,   // raw API date string (sortable)
  val audio: String? = null,      // remote audio URL
  val programId: String,
  val programName: String,
  val language: String,
  val coverUrl: String? = null,
  val attachments: List<Attachment>? = null,   // optional: records saved before this field decode fine
) {
  /** PDF attachments, the only kind we can render inline. */
  val pdfAttachments: List<Attachment> get() = attachments.orEmpty().filter { it.isPdf }

  companion object {
    fun from(episode: Episode, program: Program) = EpisodeRecord(
      id = episode.episodeId,
      title = episode.displayTitle,
      playDate = episode.releaseDate,
      audio = episode.audioUrl,
      programId = program.programId,
      programName = program.name,
      language = program.language,
      coverUrl = ChannelPlusApi.imageUrl(episode.image?.imageRef) ?: program.coverUrl,
      attachments = episode.attachments,
    )
  }
}
