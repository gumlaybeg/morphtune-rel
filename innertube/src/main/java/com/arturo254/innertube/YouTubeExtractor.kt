package com.arturo254.innertube

import okhttp3.OkHttpClient
import okhttp3.Request
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import java.io.File
import java.net.URLDecoder

object YouTubeExtractor {
    private val client = OkHttpClient.Builder().build()

    private val initLock = Any()

    private var cachedPlayerJs: String? = null
    private var deobfuscateJsCode: String? = null
    private var deobfuscateFuncName: String? = null
    private var transformNJsCode: String? = null
    private var transformNFuncName: String? = null
    private var currentResolvedUrl: String? = null

    private var sigScope: Scriptable? = null
    private var sigFunction: Function? = null
    private var nScope: Scriptable? = null
    private var nFunction: Function? = null

    var cacheDir: File? = null

    val isReady: Boolean
        get() = deobfuscateJsCode != null && transformNJsCode != null

    fun getSignatureTimestamp(): Int {
        return synchronized(initLock) {
            try {
                val playerJs = getPlayerJs()
                val match = Regex("""signatureTimestamp:(\d+)""").find(playerJs)
                match?.groupValues?.get(1)?.toInt() ?: 0
            } catch (e: Exception) {
                0
            }
        }
    }

    fun ensureInitialized() {
        synchronized(initLock) {
            if (isReady) return
            try {
                val js = getPlayerJs()
                if (js.isNotEmpty()) {
                    runCatching { prepareSignatureDeobfuscator(js) }
                    runCatching { prepareThrottlingDeobfuscator(js) }
                }
                if (isReady) {
                    runCatching { ensureRhinoCompiled() }
                    runCatching { saveCacheIfComplete() }
                }
            } catch (e: Exception) {
                println("[YouTubeExtractor] ensureInitialized failed: ${e.message}")
            }
        }
    }

    private fun prepareSignatureDeobfuscator(js: String) {
        val funcNameRegex = Regex("""\b[cs]\s*&&\s*[a-zA-Z0-9]+\.set\([^,]+\s*,\s*encodeURIComponent\s*\(\s*([a-zA-Z0-9$]+)\(""")
        val match = funcNameRegex.find(js)
        if (match != null) {
            deobfuscateFuncName = match.groupValues[1]
            val funcBodyRegex = Regex("""(?x)(?:function\s+${deobfuscateFuncName}|var\s+${deobfuscateFuncName}\s*=\s*function)\s*\(([^)]*)\)\s*\{([^}]+)\}""")
            val funcMatch = funcBodyRegex.find(js)
            if (funcMatch != null) {
                val args = funcMatch.groupValues[1]
                val body = funcMatch.groupValues[2]
                
                val helperObjRegex = Regex(""";([a-zA-Z0-9$]+)\.[a-zA-Z0-9$]+\(""")
                val helperObjMatch = helperObjRegex.find(body)
                var helperCode = ""
                if (helperObjMatch != null) {
                    val helperName = helperObjMatch.groupValues[1]
                    val helperRegex = Regex("""var\s+${helperName}\s*=\s*\{[\s\S]*?\};\s*""")
                    helperCode = helperRegex.find(js)?.value ?: ""
                }
                deobfuscateJsCode = "$helperCode\nfunction ${deobfuscateFuncName}($args) {$body}"
            }
        }
    }

    private fun prepareThrottlingDeobfuscator(js: String) {
        val funcNameRegex = Regex("""\b[a-zA-Z0-9$]+\s*&&\s*[a-zA-Z0-9]+\.set\([^,]+\s*,\s*encodeURIComponent\s*\(\s*([a-zA-Z0-9$]+)\(""")
        val match = funcNameRegex.find(js)
        if (match != null) {
            transformNFuncName = match.groupValues[1]
            val funcBodyRegex = Regex("""(?x)(?:function\s+${transformNFuncName}|var\s+${transformNFuncName}\s*=\s*function)\s*\(([^)]*)\)\s*\{([^}]+)\}""")
            val funcMatch = funcBodyRegex.find(js)
            if (funcMatch != null) {
                val args = funcMatch.groupValues[1]
                val body = funcMatch.groupValues[2]
                transformNJsCode = "function ${transformNFuncName}($args) {$body}"
            }
        }
    }

    private fun ensureRhinoCompiled() {
        val ctx = Context.enter()
        ctx.optimizationLevel = -1
        try {
            if (deobfuscateJsCode != null && deobfuscateFuncName != null) {
                sigScope = ctx.initStandardObjects()
                ctx.evaluateString(sigScope, deobfuscateJsCode, "sig", 1, null)
                sigFunction = sigScope?.get(deobfuscateFuncName, sigScope) as? Function
            }
            if (transformNJsCode != null && transformNFuncName != null) {
                nScope = ctx.initStandardObjects()
                ctx.evaluateString(nScope, transformNJsCode, "n", 1, null)
                nFunction = nScope?.get(transformNFuncName, nScope) as? Function
            }
        } finally {
            Context.exit()
        }
    }

    fun decryptUrl(signatureCipher: String): String? {
        ensureInitialized()
        val params = signatureCipher.split("&").associate {
            val parts = it.split("=")
            parts[0] to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
        val url = params["url"] ?: return null
        val sig = params["s"]
        val sp = params["sp"] ?: "sig"
        
        var decryptedSig = sig
        if (sig != null && sigFunction != null) {
            val ctx = Context.enter()
            ctx.optimizationLevel = -1
            try {
                decryptedSig = sigFunction?.call(ctx, sigScope, sigScope, arrayOf(sig)) as? String
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                Context.exit()
            }
        }
        
        val nParamUrl = if (decryptedSig != null) "$url&$sp=$decryptedSig" else url
        return deobfuscateUrlNParam(nParamUrl)
    }

    fun deobfuscateUrlNParam(url: String): String {
        ensureInitialized()
        val nMatch = Regex("""&n=([^&]+)""").find(url) ?: return url
        val nToken = nMatch.groupValues[1]
        var decryptedN = nToken
        
        if (nFunction != null) {
            val ctx = Context.enter()
            ctx.optimizationLevel = -1
            try {
                decryptedN = nFunction?.call(ctx, nScope, nScope, arrayOf(nToken)) as? String ?: nToken
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                Context.exit()
            }
        }
        
        return url.replace("&n=$nToken", "&n=$decryptedN")
    }

    private fun loadCache(resolvedPlayerJsUrl: String): Boolean {
        val dir = cacheDir ?: return false
        try {
            val cachedUrlFile = File(dir, "yt_player_url.txt")
            if (!cachedUrlFile.exists()) return false
            val cachedUrl = cachedUrlFile.readText().trim()
            if (cachedUrl != resolvedPlayerJsUrl) {
                return false
            }

            val sigJsFile = File(dir, "yt_sig_js.txt")
            val sigFuncFile = File(dir, "yt_sig_func.txt")
            val nJsFile = File(dir, "yt_n_js.txt")
            val nFuncFile = File(dir, "yt_n_func.txt")

            if (sigJsFile.exists() && sigFuncFile.exists() && nJsFile.exists() && nFuncFile.exists()) {
                deobfuscateJsCode = sigJsFile.readText()
                deobfuscateFuncName = sigFuncFile.readText().trim()
                transformNJsCode = nJsFile.readText()
                transformNFuncName = nFuncFile.readText().trim()
                return true
            }
        } catch (e: Exception) {
        }
        return false
    }

    private fun saveCacheIfComplete() {
        val resolvedUrl = currentResolvedUrl ?: return
        val dir = cacheDir ?: return
        if (deobfuscateJsCode != null && transformNJsCode != null) {
            try {
                File(dir, "yt_player_url.txt").writeText(resolvedUrl)
                File(dir, "yt_player_cache_time.txt").writeText(System.currentTimeMillis().toString())
                deobfuscateJsCode?.let { File(dir, "yt_sig_js.txt").writeText(it) }
                deobfuscateFuncName?.let { File(dir, "yt_sig_func.txt").writeText(it) }
                transformNJsCode?.let { File(dir, "yt_n_js.txt").writeText(it) }
                transformNFuncName?.let { File(dir, "yt_n_func.txt").writeText(it) }
            } catch (e: Exception) {
            }
        }
    }

    private fun fetchUrl(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("HTTP error: ${response.code}")
            }
            return response.body?.string() ?: ""
        }
    }

    private fun getPlayerJs(): String {
        cachedPlayerJs?.let { return it }

        val dir = cacheDir
        if (dir != null) {
            try {
                val timeFile = File(dir, "yt_player_cache_time.txt")
                if (timeFile.exists()) {
                    val lastSaved = timeFile.readText().trim().toLongOrNull() ?: 0L
                    val age = System.currentTimeMillis() - lastSaved
                    if (age in 0 until (24L * 3600 * 1000)) {
                        val sigJsFile = File(dir, "yt_sig_js.txt")
                        val sigFuncFile = File(dir, "yt_sig_func.txt")
                        val nJsFile = File(dir, "yt_n_js.txt")
                        val nFuncFile = File(dir, "yt_n_func.txt")
                        if (sigJsFile.exists() && sigFuncFile.exists() && nJsFile.exists() && nFuncFile.exists()) {
                            deobfuscateJsCode = sigJsFile.readText()
                            deobfuscateFuncName = sigFuncFile.readText().trim()
                            transformNJsCode = nJsFile.readText()
                            transformNFuncName = nFuncFile.readText().trim()
                            cachedPlayerJs = "" 
                            return ""
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }

        // --- Cache miss: resolve player JS URL from network ---
        println("[YouTubeExtractor] Cache miss — resolving YouTube player JS URL...")
        val iframeApi = fetchUrl("https://www.youtube.com/iframe_api")
        
        // Match player hash: YouTube may use uppercase chars and embeds it in various formats
        // e.g. /s/player/b0d2d49a/  or  player\/b0d2d49a\/
        val hashMatch = Regex("""[\/\\]player[\/\\]([A-Za-z0-9]{8})[\/\\]""").find(iframeApi)
        
        val playerJsUrl = if (hashMatch != null) {
            val url = "https://www.youtube.com/s/player/${hashMatch.groupValues[1]}/player_ias.vflset/en_US/base.js"
            println("[YouTubeExtractor] Found player JS URL via iframe_api: $url")
            url
        } else {
            println("[YouTubeExtractor] iframe_api regex match failed. Trying watch embed fallback...")
            val embedPage = fetchUrl("https://www.youtube.com/embed/dQw4w9WgXcQ")
            
            // YouTube may escape slashes in JSON: "jsUrl":"\/s\/player\/..." or "jsUrl":"/s/player/..."
            val embedMatch = Regex(""""jsUrl"\s*:\s*"((?:\\/|/)[^"]+base\.js)"""").find(embedPage)
            if (embedMatch != null) {
                // Unescape any JSON-escaped forward slashes
                val rawPath = embedMatch.groupValues[1].replace("\\/", "/")
                
                // Extract the 8-char hash and ALWAYS build the canonical IAS player URL.
                // Embed page returns player_embed_es6.vflset which has a different JS structure
                // and SILENTLY breaks deobfuscation pattern matching (wrong function signatures).
                val hashFromEmbed = Regex("/player/([A-Za-z0-9]{8})/").find(rawPath)?.groupValues?.get(1)
                val url = if (hashFromEmbed != null) {
                    val canonical = "https://www.youtube.com/s/player/$hashFromEmbed/player_ias.vflset/en_US/base.js"
                    println("[YouTubeExtractor] Embed fallback: hash=$hashFromEmbed -> canonical IAS URL: $canonical")
                    canonical
                } else {
                    val full = if (rawPath.startsWith("http")) rawPath else "https://www.youtube.com$rawPath"
                    println("[YouTubeExtractor] Embed page fallback (no hash extracted): $full")
                    full
                }
                url
            } else {
                println("[YouTubeExtractor] Embed page fallback also failed!")
                "https://www.youtube.com/s/player/f98246f4/player_ias.vflset/en_US/base.js"
            }
        }
        
        currentResolvedUrl = playerJsUrl

        if (loadCache(playerJsUrl)) {
            return ""
        }

        val js = fetchUrl(playerJsUrl)
        cachedPlayerJs = js
        return js
    }
}
