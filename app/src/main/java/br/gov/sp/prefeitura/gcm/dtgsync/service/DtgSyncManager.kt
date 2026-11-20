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
 * 1. Mapear as pastas de voo do DJI Pilot 2 no rádio Android (com.dji.industry.pilot).
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
        private const val KEY_CUSTOM_SERIAL = "custom_radio_serial"
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
        val custom = prefs.getString(KEY_CUSTOM_SERIAL, "")
        if (!custom.isNullOrBlank()) return custom

        val hostname = try {
            val p = Runtime.getRuntime().exec("getprop net.hostname")
            p.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) {
            ""
        }
        if (hostname.isNotBlank() && hostname != "localhost") return hostname

        val serial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { Build.getSerial() } catch (e: Exception) { Build.SERIAL }
        } else {
            Build.SERIAL
        }
        return if (serial != "unknown" && serial.isNotBlank()) "rc-$serial" else "dronepol-rcplus-${Build.MODEL.replace(" ", "-")}"
    }

    fun setRadioIdentifier(serial: String) {
        prefs.edit().putString(KEY_CUSTOM_SERIAL, serial.trim()).apply()
    }

    fun getSelectedInspetoria(): String {
        return prefs.getString(KEY_INSPETORIA, "Divisão de Tecnologias Geoespaciais (DTG)") ?: "DTG"
    }

    fun setSelectedInspetoria(inspetoria: String) {
        prefs.edit().putString(KEY_INSPETORIA, inspetoria).apply()
    }

    fun getServerUrl(): String {
        return "https://dtg.yuriodilon.com/manage/api/v1/devices/flight-records/upload"
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

        // 1. Validar se o servidor DTG Hub está acessível
        listener?.onLogMessage("[REDE] Verificando conexão com o DTG Hub...")
        val isReachable = NetworkUtils.isServerReachable()
        if (!isReachable) {
            listener?.onLogMessage("[ALERTA] Servidor DTG Hub inalcançável. Verifique se a rede/Tailscale está conectada.")
            listener?.onSyncFinished(0, 0)
            return@withContext Pair(0, 0)
        }

        val baseUrl = NetworkUtils.getActiveServerBaseUrl()
        val uploadUrl = "$baseUrl/manage/api/v1/devices/flight-records/upload"
        listener?.onLogMessage("[REDE] Servidor online! Rota ativa: $uploadUrl")

        // 2. Localizar arquivos de voo nos diretórios do DJI Pilot 2
        listener?.onLogMessage("[DISCO] Mapeando diretórios de voo do DJI Pilot 2 no armazenamento interno...")
        val flightFiles = findFlightRecordFiles(listener)

        if (flightFiles.isEmpty()) {
            listener?.onLogMessage("[DISCO] Nenhum arquivo de voo (.txt, .dat, .csv) foi localizado nos diretórios do rádio.")
            listener?.onLogMessage("[DICA] Certifique-se de que a permissão 'Acesso a todos os arquivos' está ativa nas configurações.")
            listener?.onSyncFinished(0, 0)
            return@withContext Pair(0, 0)
        }

        val syncedHashes = getSyncedHashes()
        val pendingFiles = flightFiles.filter { file ->
            val hash = SecurityUtils.calculateFileSha256(file)
            !syncedHashes.contains(hash)
        }

        if (pendingFiles.isEmpty()) {
            listener?.onLogMessage("[OK] Todos os ${flightFiles.size} voos existentes já estão sincronizados com a Central da GCM!")
            listener?.onSyncFinished(0, 0)
            return@withContext Pair(0, 0)
        }

        listener?.onLogMessage("[PENDENTES] Detectados ${pendingFiles.size} novo(s) voo(s) para enviar.")

        var success = 0
        var failed = 0

        val radioSerial = getRadioIdentifier()
        val inspetoria = getSelectedInspetoria()

        pendingFiles.forEachIndexed { index, file ->
            val hash = SecurityUtils.calculateFileSha256(file)
            listener?.onSyncProgress(index + 1, pendingFiles.size)
            listener?.onLogMessage("[UPLOAD ${index + 1}/${pendingFiles.size}] Enviando ${file.name} (${file.length() / 1024} KB)...")

            val result = uploadSingleFlightFile(file, hash, radioSerial, inspetoria, uploadUrl)
            if (result.isSuccess) {
                markHashAsSynced(hash)
                success++
                listener?.onLogMessage("[SUCESSO] ${file.name} sincronizado com a Central!")
            } else {
                failed++
                listener?.onLogMessage("[ERRO ${file.name}] ${result.errorMessage ?: "Falha ao enviar"}")
            }

            // Pausa controlada para manter a fluidez da UI e evitar saturação do socket
            kotlinx.coroutines.delay(30)
        }

        listener?.onLogMessage("[FIM] Sincronização concluída: $success enviado(s), $failed falha(s).")
        listener?.onSyncFinished(success, failed)
        Pair(success, failed)
    }

    /**
     * Encontra todos os arquivos de voo reais (.txt, .csv) nas pastas conhecidas do DJI Pilot 2,
     * ignorando sumariamente a subpasta MCDatFlightRecords (dumps brutos do controlador de voo).
     */
    private fun findFlightRecordFiles(listener: SyncLogListener? = null): List<File> {
        val foundFiles = mutableListOf<File>()
        val externalStorage = Environment.getExternalStorageDirectory()

        // Diretórios oficiais de registros de voo do DJI Pilot 2 no Android (DJI RC Plus / Enterprise)
        val candidatePaths = listOf(
            File(externalStorage, "DJI/com.dji.industry.pilot/FlightRecord"),
            File(externalStorage, "Android/data/com.dji.industry.pilot/files/FlightRecord"),
            File(externalStorage, "DJI/dji.pilot.2/FlightRecord"),
            File(externalStorage, "DJI/FlightRecord"),
            File(externalStorage, "Android/data/dji.pilot.2/files/FlightRecord"),
            File("/sdcard/DJI/com.dji.industry.pilot/FlightRecord"),
            File("/sdcard/DJI/dji.pilot.2/FlightRecord"),
            File("/sdcard/DJI/FlightRecord")
        )

        for (dir in candidatePaths) {
            try {
                if (dir.exists() && dir.isDirectory) {
                    val files = dir.listFiles { file ->
                        file.isFile && (file.extension.equals("txt", ignoreCase = true) ||
                                file.extension.equals("csv", ignoreCase = true))
                    }
                    if (!files.isNullOrEmpty()) {
                        listener?.onLogMessage("[DISCO] Pasta ativa: ${dir.path} (${files.size} registros de voo)")
                        foundFiles.addAll(files)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao acessar ${dir.path}: ${e.message}")
            }
        }

        // Se nenhuma das pastas padrão diretas retornou voos, faz busca dinâmica dentro de /DJI
        if (foundFiles.isEmpty()) {
            val djiFolder = File(externalStorage, "DJI")
            if (djiFolder.exists() && djiFolder.isDirectory) {
                listener?.onLogMessage("[DISCO] Buscando dinamicamente dentro de /sdcard/DJI...")
                scanDirectoryRecursively(djiFolder, foundFiles, maxDepth = 4)
            }
        }

        // Ordena por data (mais recentes primeiro) e remove duplicatas por caminho
        val distinct = foundFiles.distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
        if (distinct.isNotEmpty()) {
            listener?.onLogMessage("[DISCO] Total de registros de voo localizados: ${distinct.size}")
        }
        return distinct
    }

    private fun scanDirectoryRecursively(dir: File, result: MutableList<File>, maxDepth: Int) {
        if (maxDepth <= 0) return
        val list = dir.listFiles() ?: return
        for (item in list) {
            // Ignora pastas de blackbox bruto interno (MCDatFlightRecords)
            if (item.isDirectory) {
                if (!item.name.startsWith(".") && !item.name.equals("MCDatFlightRecords", ignoreCase = true)) {
                    scanDirectoryRecursively(item, result, maxDepth - 1)
                }
            } else if (item.isFile && (item.extension.equals("txt", ignoreCase = true) ||
                        item.extension.equals("csv", ignoreCase = true))) {
                result.add(item)
            }
        }
    }

    /**
     * Resultado da tentativa de envio com detalhes de diagnóstico
     */
    data class UploadFileResult(
        val isSuccess: Boolean,
        val httpCode: Int? = null,
        val errorMessage: String? = null
    )

    /**
     * Faz upload multipart/form-data do arquivo de voo para o servidor DTG Hub
     */
    private fun uploadSingleFlightFile(
        file: File,
        hash: String,
        radioSerial: String,
        inspetoria: String,
        uploadUrl: String
    ): UploadFileResult {
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

            // ATENÇÃO: NÃO adicionar radioSerial ou inspetoria no cabeçalho HTTP (addHeader),
            // pois caracteres acentuados (ã, é) violam o RFC 7230 no OkHttp e causam IllegalArgumentException.
            // Ambos os parâmetros já trafegam 100% seguros em UTF-8 no corpo multipart/form-data acima.
            val request = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .addHeader("User-Agent", "DTG-Sync-Android/1.2.1 (GCM SP DRONEPOL)")
                .build()

            val response = httpClient.newCall(request).execute()
            val code = response.code
            val isSuccess = response.isSuccessful
            val responseBody = try { response.body?.string() } catch (ignored: Exception) { "" }
            response.close()

            if (isSuccess) {
                UploadFileResult(isSuccess = true, httpCode = code)
            } else {
                UploadFileResult(
                    isSuccess = false,
                    httpCode = code,
                    errorMessage = "HTTP $code" + (if (!responseBody.isNullOrBlank()) ": $responseBody" else "")
                )
            }
        } catch (e: IOException) {
            Log.e(TAG, "Erro de rede no upload de ${file.name}", e)
            UploadFileResult(isSuccess = false, errorMessage = "Falha de rede: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Erro inesperado no upload de ${file.name}", e)
            UploadFileResult(isSuccess = false, errorMessage = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
