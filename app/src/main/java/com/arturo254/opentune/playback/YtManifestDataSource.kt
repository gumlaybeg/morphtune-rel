package com.arturo254.opentune.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.arturo254.innertube.YouTube
import com.arturo254.innertube.models.YouTubeClient
import com.arturo254.innertube.pages.NewPipeExtractor
import com.arturo254.opentune.db.MusicDatabase
import com.arturo254.opentune.db.entities.FormatEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class YtManifestDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val database: MusicDatabase,
    private val context: Context
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return YtManifestDataSource(upstreamFactory.createDataSource(), database, context)
    }
}

class YtManifestDataSource(
    private val upstream: DataSource,
    private val database: MusicDatabase,
    private val context: Context
) : DataSource {
    private var manifestBytes: ByteArray? = null
    private var bytesRead = 0
    private var currentDataSpec: DataSpec? = null

    // We use the same configuration as ExoPlayer will use to ensure accurate validation
    private val httpClient = OkHttpClient.Builder().proxy(YouTube.proxy).build()

    private fun validateStatus(url: String): Boolean {
        return try {
            val request = Request.Builder().head().url(url).build()
            val response = httpClient.newCall(request).execute()
            val isSuccess = response.isSuccessful
            response.close()
            isSuccess
        } catch (e: Exception) {
            false
        }
    }

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme == "ytvideo") {
            try {
                val videoId = dataSpec.uri.host?.takeIf { it.isNotBlank() }
                    ?: dataSpec.key
                    ?: throw IOException("Missing videoId in ytvideo:// URI")

                NewPipeExtractor.init()
                val sigTimestamp = NewPipeExtractor.getSignatureTimestamp(videoId).getOrNull()

                // Prioritize clients that do not strictly enforce PoToken or specific headers
                val clients = listOf(
                    YouTubeClient.ANDROID_VR_NO_AUTH,
                    YouTubeClient.MOBILE,
                    YouTubeClient.WEB_REMIX,
                    YouTubeClient.IOS,
                    YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER
                )
                
                var validAudioFormat: com.arturo254.innertube.models.response.PlayerResponse.StreamingData.Format? = null
                var validVideoFormat: com.arturo254.innertube.models.response.PlayerResponse.StreamingData.Format? = null
                var audioUrl = ""
                var videoUrl = ""
                var playerResponse: com.arturo254.innertube.models.response.PlayerResponse? = null

                for (client in clients) {
                    val res = runBlocking(Dispatchers.IO) {
                        YouTube.player(
                            client = client,
                            videoId = videoId,
                            playlistId = null,
                            signatureTimestamp = sigTimestamp
                        ).getOrNull()
                    }
                    
                    if (res?.playabilityStatus?.status == "OK") {
                        val formats = res.streamingData?.adaptiveFormats ?: emptyList()
                        
                        // Prefer MP4 audio for better hardware compatibility
                        val aFormat = formats.filter { it.isAudio && it.mimeType.contains("mp4") }.maxByOrNull { it.bitrate }
                            ?: formats.filter { it.isAudio }.maxByOrNull { it.bitrate }
                            ?: continue

                        // Prefer MP4 video <= 720p for better hardware compatibility
                        val vFormat = formats.filter { !it.isAudio && (it.height ?: 0) <= 720 && it.mimeType.contains("mp4") }
                            .maxByOrNull { it.height ?: 0 }
                            ?: formats.filter { !it.isAudio && (it.height ?: 0) <= 720 }.maxByOrNull { it.height ?: 0 }
                            ?: formats.filter { !it.isAudio }.minByOrNull { it.height ?: 0 }

                        val aUrl = NewPipeExtractor.getStreamUrl(aFormat, videoId) ?: continue
                        
                        // Validate the stream URL to avoid 403 Forbidden errors during playback
                        if (validateStatus(aUrl)) {
                            validAudioFormat = aFormat
                            validVideoFormat = vFormat
                            audioUrl = aUrl.replace("&", "&amp;")
                            videoUrl = vFormat?.let { NewPipeExtractor.getStreamUrl(it, videoId) }?.replace("&", "&amp;") ?: ""
                            playerResponse = res
                            break
                        }
                    }
                }

                if (validAudioFormat == null || playerResponse == null) {
                    throw IOException("Failed to fetch a working (non-403) player response for video: $videoId")
                }

                val audioFormat = validAudioFormat
                val videoFormat = validVideoFormat

                val audioMime = audioFormat.mimeType.split(";")[0].trim()
                val audioBitrate = audioFormat.bitrate.takeIf { it > 0 } ?: 128000
                val videoMime = videoFormat?.mimeType?.split(";")?.get(0)?.trim() ?: "video/mp4"

                fun extractCodecs(mimeType: String): String {
                    val match = Regex("""codecs="([^"]+)"""").find(mimeType)
                        ?: Regex("""codecs=([^;]+)""").find(mimeType)
                    return match?.groupValues?.get(1) ?: ""
                }

                val audioCodecs = extractCodecs(audioFormat.mimeType).takeIf { it.isNotEmpty() } ?: if (audioMime.contains("mp4")) "mp4a.40.2" else "opus"
                val videoCodecs = videoFormat?.mimeType?.let { extractCodecs(it) }?.takeIf { it.isNotEmpty() } ?: if (videoMime.contains("mp4")) "avc1.4d401e" else "vp9"

                // Extract duration for static MPD manifest
                val durationMs = audioFormat.approxDurationMs?.toLongOrNull() 
                    ?: (playerResponse.videoDetails?.lengthSeconds?.toLongOrNull()?.times(1000)) 
                    ?: 0L
                val durationSec = durationMs / 1000.0

                val dashManifest = buildString {
                    append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\" mediaPresentationDuration=\"PT${durationSec}S\">\n")
                    append("  <Period>\n")
                    if (videoFormat != null && videoUrl.isNotBlank()) {
                        append("    <AdaptationSet id=\"0\" mimeType=\"$videoMime\">\n")
                        append("      <Representation id=\"video\" bandwidth=\"${videoFormat.bitrate}\" codecs=\"$videoCodecs\">\n")
                        append("        <BaseURL>$videoUrl</BaseURL>\n")
                        append("      </Representation>\n")
                        append("    </AdaptationSet>\n")
                    }
                    append("    <AdaptationSet id=\"1\" mimeType=\"$audioMime\">\n")
                    append("      <Representation id=\"audio\" bandwidth=\"$audioBitrate\" codecs=\"$audioCodecs\">\n")
                        append("        <BaseURL>$audioUrl</BaseURL>\n")
                    append("      </Representation>\n")
                    append("    </AdaptationSet>\n")
                    append("  </Period>\n")
                    append("</MPD>")
                }

                manifestBytes = dashManifest.toByteArray()

                runBlocking(Dispatchers.IO) {
                    database.query {
                        upsert(
                            FormatEntity(
                                id = videoId,
                                itag = audioFormat.itag,
                                mimeType = audioMime,
                                codecs = audioCodecs,
                                bitrate = audioFormat.bitrate,
                                sampleRate = audioFormat.audioSampleRate ?: 44100,
                                contentLength = audioFormat.contentLength ?: 0L,
                                loudnessDb = playerResponse.playerConfig?.audioConfig?.loudnessDb,
                                playbackUrl = audioUrl.replace("&amp;", "&")
                            )
                        )
                    }
                }

                bytesRead = 0
                currentDataSpec = dataSpec
                return manifestBytes!!.size.toLong()
            } catch (e: Exception) {
                e.printStackTrace()
                throw IOException("YouTube stream extraction failed: ${e.message}", e)
            }
        } else {
            return upstream.open(dataSpec)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (manifestBytes != null) {
            if (bytesRead >= manifestBytes!!.size) return C.RESULT_END_OF_INPUT
            val bytesToRead = minOf(length, manifestBytes!!.size - bytesRead)
            System.arraycopy(manifestBytes!!, bytesRead, buffer, offset, bytesToRead)
            bytesRead += bytesToRead
            return bytesToRead
        }
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = if (manifestBytes != null) currentDataSpec?.uri else upstream.uri
    
    override fun close() { 
        if (manifestBytes != null) {
            manifestBytes = null 
        } else {
            upstream.close() 
        }
    }
}
