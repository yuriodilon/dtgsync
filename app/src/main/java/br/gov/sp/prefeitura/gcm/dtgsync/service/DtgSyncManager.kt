package br.gov.sp.prefeitura.gcm.dtgsync.service

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import br.gov.sp.prefeitura.gcm.dtgsync.util.NetworkUtils
import br.gov.sp.prefeitura.gcm.dtgsync.util.SecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Gerenciador de Sincronização de Voos (DRONEPOL - GCM SP)
 *
 * Responsável por:
 * 1. Mapear as pastas internas de voo do DJI Pilot 2 no rádio Android.
 * 2. Filtrar voos novos ainda não enviados à Central.
 * 3. Enviar via HTTP POST seguro (multipart) para o servidor DTG Hub.
 * 4. Registrar a confirmação no histórico local para nunca duplicar dados.
 */
class DtgSyncManager(private val context: Context) {

    companion object {
        private const val TAG = "DtgSyncManager"
        private const val PREFS_NAME = "dtg_sync_prefs"
        private const val KEY_SYNCED_HASHES = "synced_file_hashes"
        private const val KEY_LAST_SYNC_TIME = "last_sync_timestamp"
        private const val KEY_INSPETORIA = "selected_inspetoria"
        private const val KEY_SERVER_URL = "server_api_url"

        // Diretórios onde o DJI Pilot 2 armazena os arquivos de histórico de voo
        val FLIGHT_RECORD_PATHS = listOf(
            "/sdcard/DJI/dji.pilot.2/FlightRecord",
            "/sdcard/Android/data/dji.pilot.2/files/FlightRecord",
            "/sdcard/Android/data/dji.pilot.2/files/FlightRecord/MCDatFlightRecords",
            "/sdcard/DJI/FlightRecord"
        )
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Cliente HTTP OkHttp com timeouts otimizados para links de campo
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Interface para escutar eventos de log e status em tempo real na interface
     */
    interface SyncLogListener {
        fun onLogMessage(message: String)
        fun onSyncProgress(current: Int, total: Int)
        fun onSyncFinished(successCount: Int, failedCount: Int)
    }

    /**
     * Obtém o identificador único do rádio controle (Serial / Hostname)
     */
    fun getRadioIdentifier(): String {
        val hostname = try {
            val p = Runtime.getRuntime().exec("getprop net.hostname")
            p.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) {
            ""
        }
        if (hostname.isNotEmpty()) return hostname

        val serial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { Build.getSerial() } catch (e: Exception) { Build.SERIAL }
        } else {
            Build.SERIAL
        }
        return if (serial != "unknown") "rc-$serial" else "dronepol-rcplus-${Build.MODEL.replace(" ", "-")}"
    }

    fun getSelectedInspetoria(): String {
        return prefs.getString(KEY_INSPETORIA, "Divisão de Tecnologias Geoespaciais (DTG)") ?: "DTG"
    }

    fun setSelectedInspetoria(inspetoria: String) {
        prefs.edit().putString(KEY_INSPETORIA, inspetoria).apply()
    }

    fun getServerUrl(): String {
        return prefs.getString(KEY_SERVER_URL, "https://dtg.yuriodilon.com/manage/api/v1/devices/flight-records/upload")
            ?: "https://dtg.yuriodilon.com/manage/api/v1/devices/flight-records/upload"
    }

    fun getSyncedCount(): Int {
        return getSyncedHashes().size
    }

    fun getLastSyncTimeString(): String {
        val lastTime = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
        if (lastTime == 0L) return "Nenhuma realizada ainda"
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR"))
        return sdf.format(Date(lastTime))
    }

    private fun getSyncedHashes(): MutableSet<String> {
        return prefs.getStringSet(KEY_SYNCED_HASHES, emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    private fun markHashAsSynced(hash: String) {
        val current = getSyncedHashes()
        current.add(hash)
        prefs.edit()
            .putStringSet(KEY_SYNCED_HASHES, current)
            .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
            .apply()
    }

    /**
     * Executa a varredura completa nas pastas do rádio e envia os voos pendentes
     */
    suspend fun executeSync(listener: SyncLogListener? = null): Pair<Int, Int> = withContext(Dispatchers.IO) {
        listener?.onLogMessage("[INÍCIO] Iniciando rotina de verificação de voos...")

        // 1. Validar se o servidor DTG Hub está acessível via Tailscale
        listener?.onLogMessage("[REDE] Verificando conexão segura com DTG Hub (100.66.229.107)...")
        val isReachable = NetworkUtils.isServerReachable()
        if (!isReachable) {
            listener?.onLogMessage("[ALERTA] Servidor DTG Hub inalcançável. Verifique se a VPN Tailscale está ativa.")
            listener?.onSyncFinished(0, 0)
            return@withContext Pair(0, 0)
        }
        listener?.onLogMessage("[REDE] Servidor DTG Hub online e respondendo com baixa latência!")

        // 2. Localizar arquivos de voo nos diretórios do DJI Pilot 2
        val flightFiles = findFlightRecordFiles()
        listener?.onLogMessage("[DISCO] Localizados ${flightFiles.size} arquivos de voo no rádio.")

        val syncedHashes = getSyncedHashes()
        val pendingFiles = flightFiles.filter { file ->
            val hash = SecurityUtils.calculateFileSha256(file)
            !syncedHashes.contains(hash)
        }

        if (pendingFiles.isEmpty()) {
            listener?.onLogMessage("[OK] Todos os voos já estão sincronizados com a Central da GCM!")
            listener?.onSyncFinished(0, 0)
            return@withContext Pair(0, 0)
        }

        listener?.onLogMessage("[PENDENTES] Detectados ${pendingFiles.size} novo(s) voo(s) para enviar.")

        var success = 0
        var failed = 0

        val radioSerial = getRadioIdentifier()
        val inspetoria = getSelectedInspetoria()
        val serverUrl = getServerUrl()

        pendingFiles.forEachIndexed { index, file ->
            val hash = SecurityUtils.calculateFileSha256(file)
            listener?.onSyncProgress(index + 1, pendingFiles.size)
            listener?.onLogMessage("[UPLOAD ${index + 1}/${pendingFiles.size}] Enviando ${file.name} (${file.length() / 1024} KB)...")

            val uploaded = uploadSingleFlightFile(file, hash, radioSerial, inspetoria, serverUrl)
            if (uploaded) {
                markHashAsSynced(hash)
                success++
                listener?.onLogMessage("[SUCESSO] ${file.name} recebido e registrado na Central com sucesso.")
            } else {
                failed++
                listener?.onLogMessage("[ERRO] Falha ao enviar ${file.name}. Tentará novamente no próximo ciclo.")
            }
        }

        listener?.onLogMessage("[FIM] Sincronização concluída: $success enviado(s), $failed falha(s).")
        listener?.onSyncFinished(success, failed)
        Pair(success, failed)
    }

    /**
     * Encontra todos os arquivos de voo (.txt, .dat, .csv) nas pastas conhecidas do DJI Pilot 2
     */
    private fun findFlightRecordFiles(): List<File> {
        val foundFiles = mutableListOf<File>()

        for (path in FLIGHT_RECORD_PATHS) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles { file ->
                    file.isFile && (file.extension.equals("txt", ignoreCase = true) ||
                            file.extension.equals("dat", ignoreCase = true) ||
                            file.extension.equals("csv", ignoreCase = true))
                }
                if (files != null) {
                    foundFiles.addAll(files)
                }
            }
        }

        // Tenta também a pasta raiz pública /DJI caso esteja em outro ponto de montagem
        val externalStorage = Environment.getExternalStorageDirectory()
        val defaultDjiDir = File(externalStorage, "DJI/dji.pilot.2/FlightRecord")
        if (defaultDjiDir.exists() && defaultDjiDir.isDirectory) {
            defaultDjiDir.listFiles()?.filter { it.isFile }?.let { foundFiles.addAll(it) }
        }

        // Ordena por data (mais recentes primeiro) e remove duplicatas por caminho
        return foundFiles.distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
    }

    /**
     * Faz upload multipart/form-data do arquivo de voo para o servidor DTG Hub
     */
    private fun uploadSingleFlightFile(
        file: File,
        hash: String,
        radioSerial: String,
        inspetoria: String,
        serverUrl: String
    ): Boolean {
        return try {
            val mediaType = "application/octet-stream".toMediaTypeOrNull()
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("radio_serial", radioSerial)
                .addFormDataPart("inspetoria", inspetoria)
                .addFormDataPart("file_hash", hash)
                .addFormDataPart("original_filename", file.name)
                .addFormDataPart("file_size", file.length().toString())
                .addFormDataPart(
                    "flight_file",
                    file.name,
                    file.asRequestBody(mediaType)
                )
                .build()

            val request = Request.Builder()
                .url(serverUrl)
                .post(requestBody)
                .addHeader("User-Agent", "DTG-Sync-Android/1.0 (GCM SP DRONEPOL)")
                .addHeader("X-Radio-Serial", radioSerial)
                .addHeader("X-Inspetoria", inspetoria)
                .build()

            val response = httpClient.newCall(request).execute()
            val isSuccess = response.isSuccessful
            response.close()
            isSuccess
        } catch (e: IOException) {
            Log.e(TAG, "Erro de rede no upload de ${file.name}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Erro inesperado no upload de ${file.name}", e)
            false
        }
    }
}
