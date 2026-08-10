package com.arturo254.innertube.pages

import com.arturo254.innertube.YouTubeExtractor
import com.arturo254.innertube.models.response.PlayerResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response
import java.util.concurrent.TimeUnit

class DownloaderImpl private constructor() : Downloader() {
    val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: ExtractorRequest): Response {
        val builder = Request.Builder().url(request.url())
        
        request.headers()?.forEach { (key, values) ->
            values.forEach { builder.addHeader(key, it) }
        }
        
        if (request.httpMethod() == "POST") {
            builder.post((request.dataToSend() ?: ByteArray(0)).toRequestBody(null))
        }
        
        val response = client.newCall(builder.build()).execute()
        val headers = mutableMapOf<String, List<String>>()
        response.headers.names().forEach { name ->
            headers[name] = response.headers.values(name)
        }
        
        return Response(
            response.code,
            response.message,
            headers,
            response.body?.string(),
            response.request.url.toString()
        )
    }

    companion object {
        private var instance: DownloaderImpl? = null
        fun getInstance(): DownloaderImpl {
            if (instance == null) instance = DownloaderImpl()
            return instance!!
        }
    }
}

object NewPipeExtractor {

    fun init() {
        NewPipe.init(DownloaderImpl.getInstance())
    }

    fun getSignatureTimestamp(videoId: String): Result<Int> {
        return runCatching {
            YouTubeExtractor.getSignatureTimestamp()
        }
    }

    fun getStreamUrl(
        format: PlayerResponse.StreamingData.Format,
        videoId: String
    ): String? {
        val signatureCipher = format.signatureCipher
        return if (!signatureCipher.isNullOrEmpty()) {
            YouTubeExtractor.decryptUrl(signatureCipher)
        } else if (!format.url.isNullOrEmpty()) {
            YouTubeExtractor.deobfuscateUrlNParam(format.url)
        } else {
            null
        }
    }

    fun newPipePlayer(videoId: String): List<Pair<Int, String>> {
        return emptyList()
    }
}
