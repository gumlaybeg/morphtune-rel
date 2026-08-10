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
            val videoId = dataSpec.uri.host ?: throw IOException("Invalid videoId")
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
                val videoStream = extractor.videoOnlyStreams
                    .filter { (it.resolution.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0) >= 1080 }
                    .maxByOrNull { it.resolution.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0 }
                    ?: extractor.videoStreams.maxByOrNull { it.resolution.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0 }
                
                val audioStream = extractor.audioStreams.maxByOrNull { it.averageBitrate }
                
                val dashManifest = """
                    <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static">
                      <Period>
                        ${if (videoStream != null) """
                        <AdaptationSet mimeType="${videoStream.format.mimeType.split(";")[0]}">
                          <Representation id="video" bandwidth="1500000">
                            <BaseURL>${videoStream.content.replace("&", "&amp;")}</BaseURL>
                          </Representation>
                        </AdaptationSet>
                        """ else ""}
                        ${if (audioStream != null) """
                        <AdaptationSet mimeType="${audioStream.format.mimeType.split(";")[0]}">
                          <Representation id="audio" bandwidth="${audioStream.averageBitrate}">
                            <BaseURL>${audioStream.content.replace("&", "&amp;")}</BaseURL>
                          </Representation>
                        </AdaptationSet>
                        """ else ""}
                      </Period>
                    </MPD>
                """.trimIndent()
                manifestBytes = dashManifest.toByteArray()

                runBlocking(Dispatchers.IO) {
                    database.query {
                        upsert(FormatEntity(videoId, audioStream?.format?.id ?: 0, audioStream?.format?.mimeType?.split(";")?.get(0) ?: "audio/mp4", audioStream?.format?.mimeType?.substringAfter("codecs=")?.removeSurrounding("\"") ?: "mp4a", audioStream?.averageBitrate ?: 0, 44100, 0L, null, audioStream?.content))
                    }
                }
            }
            bytesRead = 0
            currentDataSpec = dataSpec
            return manifestBytes!!.size.toLong()
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
