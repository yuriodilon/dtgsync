package br.gov.sp.prefeitura.gcm.dtgsync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import br.gov.sp.prefeitura.gcm.dtgsync.worker.FlightSyncWorker
import java.util.concurrent.TimeUnit

/**
 * Receptor de Inicialização do Sistema Operacional Android (DJI RC Plus)
 *
 * DRONEPOL - GCM SP (Divisão de Tecnologias Geoespaciais)
 * Garante que assim que o rádio for ligado por qualquer policial em qualquer Inspetoria,
 * a rotina de monitoramento de voos e conexão segura com o DTG Hub seja agendada no Android.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"

        /**
         * Agenda a sincronização periódica resiliente no WorkManager do Android.
         * Exige apenas que haja conexão de rede disponível (Wi-Fi ou Tailscale).
         */
        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicSyncRequest = PeriodicWorkRequestBuilder<FlightSyncWorker>(
                15, TimeUnit.MINUTES, // Intervalo mínimo seguro permitido pelo Android
                5, TimeUnit.MINUTES   // Janela flexível de execução para economia de bateria
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                FlightSyncWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Mantém a tarefa já agendada sem reiniciar contador
                periodicSyncRequest
            )

            Log.i(TAG, "Agendamento periódico de sincronização de voos ativado com sucesso.")
        }

        /**
         * Dispara uma sincronização imediata (OneTimeWorkRequest) com prioridade alta.
         */
        fun triggerImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val oneTimeRequest = OneTimeWorkRequestBuilder<FlightSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(oneTimeRequest)
            Log.i(TAG, "Sincronização imediata solicitada pelo operador.")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i(TAG, "DJI RC Plus inicializado. Ativando rotina do DTG Sync...")
            schedulePeriodicSync(context)
        }
    }
}
