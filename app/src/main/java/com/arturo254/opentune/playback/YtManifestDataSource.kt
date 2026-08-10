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

                // Fetch stream data using official inner clients instead of scraping the webpage.
                val playerResponse = runBlocking(Dispatchers.IO) {
                    val clients = listOf(
                        YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
                        YouTubeClient.IOS,
                        YouTubeClient.MOBILE,
                        YouTubeClient.WEB_REMIX,
                        YouTubeClient.ANDROID_VR_NO_AUTH
                    )
                    var response: com.arturo254.innertube.models.response.PlayerResponse? = null
                    for (client in clients) {
                        val res = YouTube.player(
                            client = client,
                            videoId = videoId,
                            playlistId = null,
                            signatureTimestamp = sigTimestamp
                        ).getOrNull()
                        
                        if (res?.playabilityStatus?.status == "OK") {
                            response = res
                            break
                        }
                    }
                    response
                }

                if (playerResponse == null) {
                    throw IOException("Failed to fetch player response for video: $videoId")
                }

                val formats = playerResponse.streamingData?.adaptiveFormats ?: emptyList()
                
                val audioFormat = formats.filter { it.isAudio }.maxByOrNull { it.bitrate }
                    ?: throw IOException("No audio format found")

                // Video max 720p to prevent MediaCodec crashes on phones that don't support 4K decoding
                val videoFormat = formats.filter { !it.isAudio && (it.height ?: 0) <= 720 }
                    .maxByOrNull { it.height ?: 0 }
                    ?: formats.filter { !it.isAudio }.minByOrNull { it.height ?: 0 }

                val audioUrl = NewPipeExtractor.getStreamUrl(audioFormat, videoId)?.replace("&", "&amp;") ?: ""
                val videoUrl = videoFormat?.let { NewPipeExtractor.getStreamUrl(it, videoId) }?.replace("&", "&amp;") ?: ""

                if (audioUrl.isBlank()) {
                    throw IOException("Audio URL could not be resolved")
                }

                val audioMime = audioFormat.mimeType.split(";")[0]
                val audioBitrate = audioFormat.bitrate.takeIf { it > 0 } ?: 128000
                val videoMime = videoFormat?.mimeType?.split(";")?.get(0) ?: "video/mp4"

                val dashManifest = buildString {
                    append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\">\n")
                    append("  <Period>\n")
                    if (videoFormat != null && videoUrl.isNotBlank()) {
                        append("    <AdaptationSet mimeType=\"$videoMime\">\n")
                        append("      <Representation id=\"video\" bandwidth=\"1500000\">\n")
                        append("        <BaseURL>$videoUrl</BaseURL>\n")
                        append("      </Representation>\n")
                        append("    </AdaptationSet>\n")
                    }
                    append("    <AdaptationSet mimeType=\"$audioMime\">\n")
                    append("      <Representation id=\"audio\" bandwidth=\"$audioBitrate\">\n")
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
                                codecs = audioFormat.codecs ?: "mp4a",
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
