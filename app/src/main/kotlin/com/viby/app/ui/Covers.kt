package com.viby.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/** YouTube thumbnails cached in memory and under &lt;app dir&gt;/covers. */
object CoverCache {
    @Volatile
    var directory: Path? = null

    private val memory = ConcurrentHashMap<String, ImageBitmap>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val parallel = Semaphore(6)
    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    fun peek(url: String?): ImageBitmap? = url?.let(memory::get)

    suspend fun load(url: String): ImageBitmap? {
        memory[url]?.let { return it }
        if (url in failed) return null
        return parallel.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = cached(url) ?: download(url)
                    Image.makeFromEncoded(bytes).toComposeImageBitmap().also { memory[url] = it }
                }.onFailure { failed += url }.getOrNull()
            }
        }
    }

    private fun cached(url: String): ByteArray? = file(url)?.takeIf(Files::isRegularFile)?.let(Files::readAllBytes)

    private fun download(url: String): ByteArray {
        val response = http.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()}" }
        file(url)?.let { target ->
            Files.createDirectories(target.parent)
            Files.write(target, response.body())
        }
        return response.body()
    }

    private fun file(url: String): Path? {
        val dir = directory ?: return null
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(url.toByteArray()))
        return dir.resolve("$hash.jpg")
    }
}

@Composable
fun rememberCover(url: String?): ImageBitmap? =
    produceState(CoverCache.peek(url), url) {
        value = url?.let { CoverCache.load(it) }
    }.value
