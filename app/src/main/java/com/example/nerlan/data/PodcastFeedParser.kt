package com.example.nerlan.data

import android.util.Xml
import java.io.StringReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.xmlpull.v1.XmlPullParser

/**
 * Parses a podcast RSS feed into a [PodcastFeed] (+ its episodes as
 * [EpisodeRecord]s) using the built-in [XmlPullParser] — no third-party dep,
 * matching the rest of the app. Namespace processing is off so qualified element
 * names (`itunes:image`, `itunes:duration`) arrive verbatim. Mirrors the iOS
 * `PodcastFeedParser`.
 */
object PodcastFeedParser {
  class ParseException(message: String) : Exception(message)

  private class Item {
    var title = ""; var audio = ""; var type = ""; var guid = ""
    var pubDate = ""; var duration = ""; var image = ""
  }

  fun parse(xml: String, feedUrl: String): PodcastFeed {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(StringReader(xml))

    var channelTitle = ""; var channelDesc = ""; var channelAuthor = ""
    var channelLang = ""; var channelImage = ""
    var inItem = false; var inImage = false
    val text = StringBuilder()
    var item = Item()
    val items = mutableListOf<Item>()

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      when (event) {
        XmlPullParser.START_TAG -> {
          text.setLength(0)
          when (parser.name) {
            "item" -> { inItem = true; item = Item() }
            "image" -> if (!inItem) inImage = true
            "itunes:image" -> {
              val href = parser.getAttributeValue(null, "href") ?: ""
              if (inItem) item.image = href else if (channelImage.isEmpty()) channelImage = href
            }
            "enclosure" -> if (inItem) {
              item.audio = parser.getAttributeValue(null, "url") ?: ""
              item.type = parser.getAttributeValue(null, "type") ?: ""
            }
          }
        }
        XmlPullParser.TEXT -> text.append(parser.text)
        XmlPullParser.END_TAG -> {
          val value = text.toString().trim()
          if (inItem) {
            when (parser.name) {
              "title" -> if (item.title.isEmpty()) item.title = value
              "guid" -> if (item.guid.isEmpty()) item.guid = value
              "pubDate" -> item.pubDate = value
              "itunes:duration" -> item.duration = value
              "item" -> { inItem = false; items += item }
            }
          } else {
            when (parser.name) {
              "title" -> if (!inImage && channelTitle.isEmpty()) channelTitle = value
              "description", "itunes:summary" -> if (channelDesc.isEmpty() && value.isNotEmpty()) channelDesc = value
              "itunes:author", "author" -> if (channelAuthor.isEmpty() && value.isNotEmpty()) channelAuthor = value
              "language" -> if (channelLang.isEmpty()) channelLang = value
              "url" -> if (inImage && channelImage.isEmpty()) channelImage = value
              "image" -> inImage = false
            }
          }
          text.setLength(0)
        }
      }
      event = parser.next()
    }

    val language = mappedLanguage(channelLang)
    val cover = channelImage.ifEmpty { null }
    val records = items.mapNotNull { it ->
      if (it.audio.isEmpty()) return@mapNotNull null   // unplayable without audio
      val keySource = it.guid.ifEmpty { it.audio }
      EpisodeRecord(
        id = "pod-" + sha256(keySource),
        title = it.title.ifEmpty { "（無標題）" },
        playDate = isoDate(it.pubDate),
        audio = it.audio,
        programId = feedUrl,
        programName = channelTitle,
        language = language,
        coverUrl = it.image.ifEmpty { cover },
        attachments = null,
        durationSeconds = durationSeconds(it.duration),
        audioExt = audioExt(it.type, it.audio),
      )
    }
    if (records.isEmpty()) throw ParseException("這個 RSS 沒有可播放的單集")
    return PodcastFeed(
      id = feedUrl,
      title = channelTitle,
      author = channelAuthor.ifEmpty { null },
      description = channelDesc.ifEmpty { null },
      coverUrl = cover,
      language = language,
      episodes = records,
    )
  }

  // MARK: Field helpers

  private fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
      .joinToString("") { "%02x".format(it) }

  /** `itunes:duration` is seconds ("1234"/"1234.5") or "HH:MM:SS" / "MM:SS". */
  private fun durationSeconds(s: String): Int? {
    if (s.isBlank()) return null
    if (s.contains(":")) {
      val parts = s.split(":").map { it.toIntOrNull() ?: 0 }
      return when (parts.size) {
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        2 -> parts[0] * 60 + parts[1]
        else -> null
      }
    }
    return s.toIntOrNull() ?: s.toDoubleOrNull()?.toInt()
  }

  /** Storage extension from the enclosure MIME type, else the URL path, default mp3. */
  private fun audioExt(type: String, url: String): String {
    when (type.lowercase()) {
      "audio/mpeg", "audio/mp3" -> return "mp3"
      "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac" -> return "m4a"
      "audio/ogg", "audio/opus" -> return "ogg"
      "audio/wav", "audio/x-wav" -> return "wav"
    }
    val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
    return if (ext in listOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "mp4")) ext else "mp3"
  }

  private val RFC_FORMATS = listOf(
    "EEE, dd MMM yyyy HH:mm:ss Z",
    "EEE, dd MMM yyyy HH:mm:ss zzz",
    "dd MMM yyyy HH:mm:ss Z",
    "EEE, dd MMM yyyy HH:mm Z",
  )

  /** Normalize an RFC-822 pubDate to an ISO-8601 string so it sorts/displays
   *  consistently with NER's `playDate`. Returns null if unparseable. */
  private fun isoDate(rfc822: String): String? {
    if (rfc822.isBlank()) return null
    val out = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
      .apply { timeZone = TimeZone.getTimeZone("UTC") }
    for (fmt in RFC_FORMATS) {
      val d = runCatching { SimpleDateFormat(fmt, Locale.US).parse(rfc822) }.getOrNull()
      if (d != null) return out.format(d)
    }
    return null
  }

  /** Map an RSS `<language>` code to the Chinese learning-language label the
   *  transcription prompt is primed for (see [OpenAIService.transcriptionPrompt]). */
  private fun mappedLanguage(code: String): String =
    when (code.lowercase().substringBefore("-")) {
      "en" -> "英語"; "ja" -> "日語"; "ko" -> "韓語"; "fr" -> "法語"; "de" -> "德語"
      "es" -> "西語"; "vi" -> "越南語"; "id" -> "印尼語"; "th" -> "泰語"; "zh" -> "中文"
      else -> "Podcast"
    }
}
