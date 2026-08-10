package com.arturo254.opentune.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.arturo254.innertube.pages.DownloaderImpl
import com.arturo254.innertube.pages.NewPipeExtractor
import com.arturo254.opentune.db.MusicDatabase
import com.arturo254.opentune.db.entities.FormatEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException

class YtManifestDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val database: MusicDatabase
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return YtManifestDataSource(upstreamFactory.createDataSource(), database)
    }
}

class YtManifestDataSource(
    private val upstream: DataSource,
    private val database: MusicDatabase
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
                    ?: return upstream.open(dataSpec)

                NewPipeExtractor.init()
                
                val extractor = ServiceList.YouTube.getStreamExtractor("https://youtube.com/watch?v=$videoId")
                extractor.fetchPage()
                
                val dashUrl = extractor.dashMpdUrl
                if (!dashUrl.isNullOrEmpty()) {
                    val request = okhttp3.Request.Builder().url(dashUrl).build()
                    val response = DownloaderImpl.getInstance().client.newCall(request).execute()
                    manifestBytes = response.body?.bytes() ?: throw IOException("Empty DASH manifest")
                    
                    runBlocking(Dispatchers.IO) {
                        database.query {
                            upsert(FormatEntity(videoId, 0, "application/dash+xml", "avc1, mp4a", 0, 44100, 0L, null, dashUrl))
                        }
                    }
                } else {
                    val videoStreams: List<VideoStream> = if (extractor.videoOnlyStreams.isNotEmpty()) extractor.videoOnlyStreams else extractor.videoStreams
                    
                    // FIX: Limit the background video stream to a maximum of 720p. 
                    // This prevents MediaCodec initialization failures on phones that do not support 4K/2K decoding
                    // and drastically reduces bandwidth usage.
                    val videoStream = videoStreams
                        .filter { stream -> 
                            val res = stream.getResolution()?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0
                            res <= 720
                        }
                        .maxByOrNull { it.getResolution()?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0 }
                        ?: videoStreams.minByOrNull { it.getResolution()?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0 }
                    
                    val audioStream: AudioStream? = extractor.audioStreams.maxByOrNull { it.getAverageBitrate() }
                    
                    if (videoStream == null && audioStream == null) {
                        return upstream.open(dataSpec)
                    }

                    val videoMime = videoStream?.getFormat()?.mimeType?.split(";")?.get(0) ?: "video/mp4"
                    val videoUrl = videoStream?.getUrl()?.replace("&", "&amp;") ?: ""
                    
                    val audioMime = audioStream?.getFormat()?.mimeType?.split(";")?.get(0) ?: "audio/mp4"
                    val audioUrl = audioStream?.getUrl()?.replace("&", "&amp;") ?: ""
                    val audioBitrate = audioStream?.getAverageBitrate()?.takeIf { it > 0 } ?: 128000

                    val dashManifest = """
                        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static">
                          <Period>
                            ${if (videoStream != null) """
                            <AdaptationSet mimeType="$videoMime">
                              <Representation id="video" bandwidth="1500000">
                                <BaseURL>$videoUrl</BaseURL>
                              </Representation>
                            </AdaptationSet>
                            """ else ""}
                            ${if (audioStream != null) """
                            <AdaptationSet mimeType="$audioMime">
                              <Representation id="audio" bandwidth="$audioBitrate">
                                <BaseURL>$audioUrl</BaseURL>
                              </Representation>
                            </AdaptationSet>
                            """ else ""}
                          </Period>
                        </MPD>
                    """.trimIndent()
                    manifestBytes = dashManifest.toByteArray()

                    runBlocking(Dispatchers.IO) {
                        database.query {
                            upsert(
                                FormatEntity(
                                    id = videoId,
                                    itag = audioStream?.getFormat()?.id ?: 0,
                                    mimeType = audioStream?.getFormat()?.mimeType?.split(";")?.get(0) ?: "audio/mp4",
                                    codecs = audioStream?.getFormat()?.mimeType?.substringAfter("codecs=")?.removeSurrounding("\"") ?: "mp4a",
                                    bitrate = audioStream?.getAverageBitrate() ?: 0,
                                    sampleRate = 44100,
                                    contentLength = 0L,
                                    loudnessDb = null,
                                    playbackUrl = audioStream?.getUrl()
                                )
                            )
                        }
                    }
                }
                bytesRead = 0
                currentDataSpec = dataSpec
                return manifestBytes!!.size.toLong()
            } catch (e: Exception) {
                e.printStackTrace()
                return upstream.open(dataSpec)
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
