package br.gov.sp.prefeitura.gcm.dtgsync.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import br.gov.sp.prefeitura.gcm.dtgsync.service.DtgSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Worker em Segundo Plano do Android (WorkManager)
 *
 * DRONEPOL - GCM SP (Divisão de Tecnologias Geoespaciais)
 * Este worker é disparado pelo sistema operacional Android de forma automática e resiliente:
 * - Toda vez que o rádio conectar ao Wi-Fi ou Tailscale.
 * - Periodicamente (a cada 15 a 30 minutos).
 * - Após o boot do rádio controle.
 *
 * Ele roda em segundo plano sem necessidade de nenhuma interação por parte do policial.
 */
class FlightSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "dtg_flight_sync_worker"
        private const val TAG = "FlightSyncWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Iniciando ciclo automático de sincronização do WorkManager...")

        val syncManager = DtgSyncManager(context)

        try {
            val (success, failed) = syncManager.executeSync(object : DtgSyncManager.SyncLogListener {
                override fun onLogMessage(message: String) {
                    Log.d(TAG, message)
                }

                override fun onSyncProgress(current: Int, total: Int) {
                    Log.d(TAG, "Progresso: $current de $total voos")
                }

                override fun onSyncFinished(successCount: Int, failedCount: Int) {
                    Log.i(TAG, "Ciclo finalizado. Sucessos: $successCount, Falhas: $failedCount")
                }
            })

            if (failed > 0 && success == 0) {
                // Se falhou completamente (ex: VPN conectada mas servidor ocupado), tenta de novo mais tarde
                Result.retry()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exceção não tratada durante o ciclo de sincronização", e)
            Result.retry()
        }
    }
}
