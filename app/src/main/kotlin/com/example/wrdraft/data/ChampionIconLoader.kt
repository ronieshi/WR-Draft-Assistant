package com.example.wrdraft.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
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
        imageView.contentDescription = champion.name
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP

        synchronized(cache) {
            cache.get(champion.slug)?.let {
                imageView.tag = champion.slug
                imageView.setImageBitmap(it)
                imageView.visibility = ImageView.VISIBLE
                return
            }
        }

        if (imageView.tag == champion.slug) return
        imageView.tag = champion.slug
        imageView.setImageDrawable(null)
        imageView.visibility = ImageView.INVISIBLE

        executor.execute {
            val bitmap = download(champion.slug)
            if (bitmap != null) {
                synchronized(cache) { cache.put(champion.slug, bitmap) }
            }
            mainHandler.post {
                if (imageView.tag == champion.slug) {
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

    private fun download(slug: String): Bitmap? {
        val connection = URL(ChampionLinks.championIconUrl(slug)).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
