package com.discodo.app.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.discodo.app.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class ReleaseInfo(
  val tagName: String,
  val versionName: String,
  val releaseNotes: String,
  val htmlUrl: String,
  val apkDownloadUrl: String,
  val apkFileName: String,
  val apkSizeBytes: Long,
)

sealed interface UpdateCheckResult {
  data class UpdateAvailable(val release: ReleaseInfo) : UpdateCheckResult

  data class UpToDate(val currentVersion: String) : UpdateCheckResult

  data class Failure(val message: String) : UpdateCheckResult
}

sealed interface InstallLaunchResult {
  data object Launched : InstallLaunchResult

  data object NeedsInstallPermission : InstallLaunchResult

  data class Error(val message: String) : InstallLaunchResult
}

/**
 * Checks GitHub Releases for MrSchnnurbart/Discodo, downloads APK assets,
 * and hands them off to Android's package installer via FileProvider.
 */
class AppUpdater(
  private val context: Context,
  private val releasesApiUrl: String = GITHUB_LATEST_RELEASE_API,
  private val client: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .build(),
) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun checkForUpdate(
    currentVersion: String = BuildConfig.VERSION_NAME,
  ): UpdateCheckResult =
    withContext(Dispatchers.IO) {
      runCatching {
          val request =
            Request.Builder()
              .url(releasesApiUrl)
              .header("Accept", "application/vnd.github+json")
              .header("User-Agent", "Discodo/$currentVersion")
              .build()

          client.newCall(request).execute().use { response ->
            if (response.code == 404) {
              return@use UpdateCheckResult.UpToDate(currentVersion)
            }
            if (!response.isSuccessful) {
              return@use UpdateCheckResult.Failure("Erreur GitHub (${response.code})")
            }
            val body = response.body?.string() ?: return@use UpdateCheckResult.Failure("Réponse vide")
            val releaseObj = json.parseToJsonElement(body).jsonObject
            if (releaseObj["draft"]?.jsonPrimitive?.booleanOrNull == true) {
              return@use UpdateCheckResult.UpToDate(currentVersion)
            }

            val tagName = releaseObj["tag_name"]?.jsonPrimitive?.content.orEmpty()
            val remoteVersion = normalizeVersion(tagName)
            if (remoteVersion.isEmpty() || !isNewerVersion(remoteVersion, currentVersion)) {
              return@use UpdateCheckResult.UpToDate(currentVersion)
            }

            val assets = releaseObj["assets"]?.jsonArray.orEmpty()
            val apkAsset =
              assets
                .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                .firstOrNull { asset ->
                  val name = asset["name"]?.jsonPrimitive?.content.orEmpty()
                  name.endsWith(".apk", ignoreCase = true)
                }

            val downloadUrl = apkAsset?.get("browser_download_url")?.jsonPrimitive?.content
            val apkName = apkAsset?.get("name")?.jsonPrimitive?.content ?: "Discodo-$tagName.apk"
            val apkSize = apkAsset?.get("size")?.jsonPrimitive?.longOrNull ?: -1L
            val htmlUrl =
              releaseObj["html_url"]?.jsonPrimitive?.content
                ?: "https://github.com/MrSchnnurbart/Discodo/releases"

            if (downloadUrl.isNullOrBlank()) {
              return@use UpdateCheckResult.Failure(
                "Version $tagName trouvée mais aucun APK attaché."
              )
            }

            UpdateCheckResult.UpdateAvailable(
              ReleaseInfo(
                tagName = tagName,
                versionName = remoteVersion,
                releaseNotes = releaseObj["body"]?.jsonPrimitive?.content.orEmpty().trim(),
                htmlUrl = htmlUrl,
                apkDownloadUrl = downloadUrl,
                apkFileName = apkName,
                apkSizeBytes = apkSize,
              )
            )
          }
        }
        .getOrElse { error ->
          UpdateCheckResult.Failure(error.message ?: "Impossible de contacter GitHub")
        }
    }

  suspend fun downloadApk(
    release: ReleaseInfo,
    onProgress: (Int) -> Unit,
  ): Result<File> =
    withContext(Dispatchers.IO) {
      runCatching {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        updatesDir.listFiles()?.forEach { oldFile -> oldFile.delete() }

        val targetFile = File(updatesDir, release.apkFileName.replace("/", "_"))
        val request =
          Request.Builder()
            .url(release.apkDownloadUrl)
            .header("User-Agent", "Discodo/${BuildConfig.VERSION_NAME}")
            .build()

        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            error("Téléchargement échoué (${response.code})")
          }
          val body = response.body ?: error("Corps de réponse vide")
          val totalBytes = body.contentLength().takeIf { it > 0 } ?: release.apkSizeBytes

          body.byteStream().use { input ->
            targetFile.outputStream().use { output ->
              val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
              var downloaded = 0L
              var lastReportedPct = -1

              while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                downloaded += read
                if (totalBytes > 0) {
                  val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                  if (pct != lastReportedPct) {
                    lastReportedPct = pct
                    onProgress(pct)
                  }
                }
              }
              output.flush()
            }
          }
        }
        onProgress(100)
        targetFile
      }
    }

  fun launchApkInstaller(apkFile: File): InstallLaunchResult {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
      val permissionIntent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
          )
          .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
      runCatching { context.startActivity(permissionIntent) }
      return InstallLaunchResult.NeedsInstallPermission
    }

    return runCatching {
        val apkUri =
          FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
          )
        val installIntent =
          Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
          }
        context.startActivity(installIntent)
        InstallLaunchResult.Launched
      }
      .getOrElse { error ->
        InstallLaunchResult.Error(error.message ?: "Impossible de lancer l'installateur")
      }
  }

  companion object {
    const val GITHUB_LATEST_RELEASE_API =
      "https://api.github.com/repos/MrSchnnurbart/Discodo/releases/latest"

    fun normalizeVersion(raw: String): String =
      raw.trim().removePrefix("v").removePrefix("V").substringBefore("-").substringBefore("+")

    fun isNewerVersion(remoteRaw: String, currentRaw: String): Boolean {
      val remoteParts = parseVersionParts(normalizeVersion(remoteRaw))
      val currentParts = parseVersionParts(normalizeVersion(currentRaw))
      if (remoteParts.isEmpty() || currentParts.isEmpty()) return false

      val maxLen = maxOf(remoteParts.size, currentParts.size)
      for (i in 0 until maxLen) {
        val r = remoteParts.getOrElse(i) { 0 }
        val c = currentParts.getOrElse(i) { 0 }
        if (r != c) return r > c
      }
      return false
    }

    private fun parseVersionParts(version: String): List<Int> {
      if (version.isBlank()) return emptyList()
      return version.split(".").map { segment ->
        segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
      }
    }
  }
}
