package br.gov.sp.prefeitura.gcm.dtgsync.update

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Gerenciador de Atualização Autônoma OTA (Over-The-Air) do DTG Sync
 *
 * DRONEPOL - GCM SP (Divisão de Tecnologias Geoespaciais)
 * Permite que todos os rádios controles da corporação instalados em campo
 * verifiquem, baixem e instalem automaticamente novas versões do aplicativo.
 */
class AutoUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "AutoUpdateManager"
        private const val UPDATE_FILENAME = "DTG_Sync_Update.apk"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val latestVersionCode: Int,
        val latestVersionName: String,
        val hasUpdate: Boolean,
        val downloadUrl: String,
        val changelog: String
    )

    /**
     * Consulta o servidor central para verificar se há atualização pendente
     */
    suspend fun checkUpdate(serverBaseUrl: String, radioSerial: String): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        val currentCode = getAppVersionCode()
        val url = "$serverBaseUrl/manage/api/v1/app/check-update?current_version_code=$currentCode&radio_serial=$radioSerial"

        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", "DTG-Sync-Android/AutoUpdate")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val bodyStr = response.body?.string() ?: return@withContext Result.success(null)
            val json = Gson().fromJson(bodyStr, JsonObject::class.java)

            if (json.get("code")?.asInt == 0 && json.has("data")) {
                val data = json.getAsJsonObject("data")
                val updateInfo = UpdateInfo(
                    latestVersionCode = data.get("latestVersionCode")?.asInt ?: currentCode,
                    latestVersionName = data.get("latestVersionName")?.asString ?: "1.0.0",
                    hasUpdate = data.get("hasUpdate")?.asBoolean ?: false,
                    downloadUrl = data.get("downloadUrl")?.asString ?: "",
                    changelog = data.get("changelog")?.asString ?: "Melhorias de estabilidade e segurança"
                )
                return@withContext Result.success(updateInfo)
            }
            Result.success(null)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar atualizações OTA", e)
            Result.failure(e)
        }
    }

    /**
     * Baixa o APK com notificação de progresso
     */
    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(downloadUrl).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Falha no download: HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Corpo de resposta vazio"))
            val totalBytes = body.contentLength()

            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
            val apkFile = File(downloadDir, UPDATE_FILENAME)
            if (apkFile.exists()) apkFile.delete()

            body.byteStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            val progress = ((totalRead * 100) / totalBytes).toInt()
                            onProgress(progress)
                        }
                    }
                    output.flush()
                }
            }

            Result.success(apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "Erro no download do APK OTA", e)
            Result.failure(e)
        }
    }

    /**
     * Dispara o instalador do sistema Android
     */
    fun installApk(activity: Activity, apkFile: File) {
        // Validação da permissão REQUEST_INSTALL_PACKAGES no Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                Toast.makeText(activity, "Autorize a instalação desta fonte e tente novamente.", Toast.LENGTH_LONG).show()
                return
            }
        }

        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao disparar instalador do APK", e)
            Toast.makeText(activity, "Erro ao iniciar instalação: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun getAppVersionCode(): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }

    fun getAppVersionName(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }
}
