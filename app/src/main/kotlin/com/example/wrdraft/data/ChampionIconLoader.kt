package com.example.wrdraft.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object ChampionIconLoader {
    private val cache = object : LruCache<String, Bitmap>(4 * 1024) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int =
            bitmap.byteCount / 1024
    }
    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(champion: Champion, imageView: ImageView) {
        loadUrl(ChampionLinks.championIconUrl(champion.slug), champion.name, imageView)
    }

    fun loadUrl(url: String, description: String, imageView: ImageView) {
        imageView.contentDescription = description
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP

        synchronized(cache) {
            cache.get(url)?.let {
                imageView.tag = url
                imageView.setImageBitmap(it)
                imageView.visibility = ImageView.VISIBLE
                return
            }
        }

        if (imageView.tag == url) return
        imageView.tag = url
        imageView.setImageDrawable(null)
        imageView.visibility = ImageView.INVISIBLE

        val context = imageView.context.applicationContext
        executor.execute {
            val bitmap = synchronized(cache) { cache.get(url) } ?: loadOrDownload(url, context.filesDir)
            if (bitmap != null) {
                synchronized(cache) { cache.put(url, bitmap) }
            }
            mainHandler.post {
                if (imageView.tag == url) {
                    if (bitmap == null) {
                        imageView.visibility = ImageView.INVISIBLE
                    } else {
                        imageView.setImageBitmap(bitmap)
                        imageView.visibility = ImageView.VISIBLE
                    }
                }
            }
        }
    }

    private fun loadOrDownload(url: String, filesDir: File): Bitmap? {
        val cacheDirectory = File(filesDir, "champion-icons")
        val cachedFile = File(cacheDirectory, "${url.sha256()}.webp")
        if (cachedFile.isFile) {
            BitmapFactory.decodeFile(cachedFile.absolutePath)?.let { return it }
            cachedFile.delete()
        }

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) } ?: return null
            if (cacheDirectory.exists() || cacheDirectory.mkdirs()) {
                val temporaryFile = File(cacheDirectory, "${cachedFile.name}.tmp")
                val saved = temporaryFile.outputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 90, output)
                }
                if (saved) {
                    if (!temporaryFile.renameTo(cachedFile)) temporaryFile.delete()
                } else {
                    temporaryFile.delete()
                }
            }
            bitmap
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
