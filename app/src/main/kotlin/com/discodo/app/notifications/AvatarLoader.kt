package com.discodo.app.notifications

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.util.LruCache
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads and caches circular user avatar bitmaps from Discord CDN.
 */
class AvatarLoader(
  private val client: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(3, TimeUnit.SECONDS)
      .readTimeout(3, TimeUnit.SECONDS)
      .callTimeout(4, TimeUnit.SECONDS)
      .build(),
) {
  private val cache = LruCache<String, Bitmap>(32)

  fun loadCircularAvatar(url: String?): Bitmap? {
    val cleanUrl = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    cache.get(cleanUrl)?.let { return it }

    val rawBitmap =
      runCatching {
          val request = Request.Builder().url(cleanUrl).build()
          client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
          }
        }
        .getOrNull() ?: return null

    val circular = toCircularBitmap(rawBitmap, TARGET_SIZE_PX)
    if (circular !== rawBitmap) {
      rawBitmap.recycle()
    }
    cache.put(cleanUrl, circular)
    return circular
  }

  private fun toCircularBitmap(source: Bitmap, targetSize: Int): Bitmap {
    val scaled =
      if (source.width != targetSize || source.height != targetSize) {
        Bitmap.createScaledBitmap(source, targetSize, targetSize, true)
      } else {
        source
      }
    val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val rect = Rect(0, 0, targetSize, targetSize)
    val radius = targetSize / 2f

    canvas.drawARGB(0, 0, 0, 0)
    canvas.drawCircle(radius, radius, radius, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(scaled, rect, rect, paint)

    if (scaled !== source && scaled !== output) {
      scaled.recycle()
    }
    return output
  }

  private companion object {
    const val TARGET_SIZE_PX = 128
  }
}
