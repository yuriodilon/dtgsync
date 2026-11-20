package br.gov.sp.prefeitura.gcm.dtgsync

import android.Manifest
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import br.gov.sp.prefeitura.gcm.dtgsync.databinding.ActivityMainBinding
import br.gov.sp.prefeitura.gcm.dtgsync.receiver.BootReceiver
import br.gov.sp.prefeitura.gcm.dtgsync.service.DtgSyncManager
import br.gov.sp.prefeitura.gcm.dtgsync.update.AutoUpdateManager
import br.gov.sp.prefeitura.gcm.dtgsync.util.NetworkUtils
import br.gov.sp.prefeitura.gcm.dtgsync.vpn.WireGuardManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tela Principal de Monitoramento e Gestão Tática do DTG Sync
 *
 * DRONEPOL - GCM SP (Divisão de Tecnologias Geoespaciais)
 * Integração:
 * 1. Túnel VPN WireGuard Nativo de Sistema (VpnService) para todo o rádio (Pilot 2, MQTT e RTMP).
 * 2. Sincronizador Automático e Resiliente de Logs de Voo do DJI Pilot 2.
 * 3. Mecanismo de Auto-Update OTA (Over-The-Air) para atualização autônoma da frota.
 */
class MainActivity : AppCompatActivity(), DtgSyncManager.SyncLogListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var syncManager: DtgSyncManager
    private lateinit var wireGuardManager: WireGuardManager
    private lateinit var autoUpdateManager: AutoUpdateManager

    companion object {
        private const val STORAGE_PERMISSION_CODE = 1001
        private const val VPN_PERMISSION_CODE = 1002

        // Inspetorias Regionais da Guarda Civil Metropolitana de São Paulo
        val INSPETORIAS_GCM = listOf(
            "Divisão de Tecnologias Geoespaciais (DTG - Central)",
            "Inspetoria Regional Sé (IR-SE)",
            "Inspetoria Regional Ibirapuera (IR-IB)",
            "Inspetoria Regional Mooca (IR-MO)",
            "Inspetoria Regional Santana (IR-SN)",
            "Inspetoria Regional Capela do Socorro (IR-CS)",
            "Inspetoria Regional Itaquera (IR-IT)",
            "Inspetoria Regional Lapa (IR-LA)",
            "Inspetoria Regional Pinheiros (IR-PI)",
            "Inspetoria de Operações Especiais (IOPE)",
            "Inspetoria de Ações Integradas (IAI)",
            "Comando Geral da GCM SP"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        syncManager = DtgSyncManager(this)
        wireGuardManager = WireGuardManager.getInstance(this)
        autoUpdateManager = AutoUpdateManager(this)

        initViews()
        setupInspetoriaSpinner()
        setupWireGuardControls()
        setupAutoUpdate()
        checkAndRequestStoragePermissions()

        // Garante que o WorkManager periódico esteja ativado no rádio
        BootReceiver.schedulePeriodicSync(this)

        // Checagem de conectividade com o servidor DTG Hub
        checkServerConnection()

        // Checagem silenciosa de atualização OTA no início
        checkForOtaUpdatesSilently()
    }

    private fun initViews() {
        binding.tvRadioSerial.text = syncManager.getRadioIdentifier()
        binding.tvServerHost.text = syncManager.getServerUrl()
        binding.tvAppVersion.text = "v${autoUpdateManager.getAppVersionName()}"
        updateStatsUi()

        binding.btnCheckConnection.setOnClickListener {
            checkServerConnection()
        }

        binding.btnSyncNow.setOnClickListener {
            triggerManualSync()
        }

        binding.btnClearLogs.setOnClickListener {
            binding.tvLogs.text = "[LOGS LIMPOS PELO OPERADOR]"
        }
    }

    private fun setupInspetoriaSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            INSPETORIAS_GCM
        )
        binding.spinnerInspetoria.adapter = adapter

        val currentSaved = syncManager.getSelectedInspetoria()
        val index = INSPETORIAS_GCM.indexOf(currentSaved)
        if (index >= 0) {
            binding.spinnerInspetoria.setSelection(index)
        }

        binding.spinnerInspetoria.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = INSPETORIAS_GCM[position]
                syncManager.setSelectedInspetoria(selected)
                appendLog("[INSPETORIA] Unidade vinculada: $selected")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ==========================================
    // 1. GESTÃO DA VPN WIREGUARD EMBUTIDA
    // ==========================================
    private fun setupWireGuardControls() {
        binding.tvVpnEndpoint.text = wireGuardManager.getServerEndpoint()
        binding.tvVpnPubKey.text = wireGuardManager.getLocalPublicKey()

        val isVpnActive = wireGuardManager.isTunnelActive()
        binding.switchWireguard.isChecked = isVpnActive
        updateVpnUi(isVpnActive)

        binding.switchWireguard.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startWireGuardTunnel()
            } else {
                stopWireGuardTunnel()
            }
        }
    }

    private fun startWireGuardTunnel() {
        val prepareIntent = wireGuardManager.prepareVpnIntent(this)
        if (prepareIntent != null) {
            // Solicita permissão padrão de VPN do Android
            startActivityForResult(prepareIntent, VPN_PERMISSION_CODE)
        } else {
            // Permissão já concedida anteriormente, sobe o túnel
            connectWireGuard()
        }
    }

    private fun connectWireGuard() {
        appendLog("[WIREGUARD] Iniciando túnel de rede corporativa...")
        binding.tvVpnStatus.text = "● CONECTANDO WIREGUARD..."
        binding.tvVpnStatus.setTextColor(ContextCompat.getColor(this, R.color.dtg_warning_amber))

        lifecycleScope.launch {
            val result = wireGuardManager.startTunnel()
            if (result.isSuccess) {
                updateVpnUi(true)
                appendLog("[WIREGUARD] Túnel conectado com sucesso! Rádio integrado à rede 100.66.229.107.")
                checkServerConnection()
            } else {
                updateVpnUi(false)
                binding.switchWireguard.isChecked = false
                appendLog("[WIREGUARD ERRO] Falha ao iniciar túnel: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    private fun stopWireGuardTunnel() {
        appendLog("[WIREGUARD] Desconectando túnel corporativo...")
        lifecycleScope.launch {
            wireGuardManager.stopTunnel()
            updateVpnUi(false)
            appendLog("[WIREGUARD] Túnel desconectado com sucesso.")
            checkServerConnection()
        }
    }

    private fun updateVpnUi(isActive: Boolean) {
        if (isActive) {
            binding.tvVpnStatus.text = "● WIREGUARD CONECTADO (100.66.229.107)"
            binding.tvVpnStatus.setTextColor(ContextCompat.getColor(this, R.color.dtg_success_green))
        } else {
            binding.tvVpnStatus.text = "○ WIREGUARD DESCONECTADO"
            binding.tvVpnStatus.setTextColor(ContextCompat.getColor(this, R.color.dtg_danger_red))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_CODE) {
            if (resultCode == RESULT_OK) {
                connectWireGuard()
            } else {
                binding.switchWireguard.isChecked = false
                appendLog("[WIREGUARD] Permissão de VPN recusada pelo operador.")
            }
        }
    }

    // ==========================================
    // 2. GESTÃO DE ATUALIZAÇÃO AUTÔNOMA OTA
    // ==========================================
    private fun setupAutoUpdate() {
        binding.btnCheckUpdate.setOnClickListener {
            checkForOtaUpdates(manualCheck = true)
        }
    }

    private fun checkForOtaUpdatesSilently() {
        checkForOtaUpdates(manualCheck = false)
    }

    private fun checkForOtaUpdates(manualCheck: Boolean) {
        if (manualCheck) {
            appendLog("[OTA] Buscando novas versões no servidor DTG Hub...")
        }

        val baseUrl = "https://dtg.yuriodilon.com"
        val radioSerial = syncManager.getRadioIdentifier()

        lifecycleScope.launch {
            val res = autoUpdateManager.checkUpdate(baseUrl, radioSerial)
            res.onSuccess { info ->
                if (info != null && info.hasUpdate) {
                    appendLog("[OTA] Nova versão disponível: ${info.latestVersionName} (v${info.latestVersionCode})")
                    showUpdateDialog(info)
                } else if (manualCheck) {
                    appendLog("[OTA] O aplicativo já está na versão mais recente.")
                    Toast.makeText(this@MainActivity, "DTG Sync atualizado na versão mais recente!", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { err ->
                if (manualCheck) {
                    appendLog("[OTA ERRO] Falha ao verificar atualizações: ${err.message}")
                }
            }
        }
    }

    private fun showUpdateDialog(info: AutoUpdateManager.UpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle("Atualização Disponível • DTG Sync")
            .setMessage("Nova versão ${info.latestVersionName} disponível para a frota.\n\nNovidades:\n${info.changelog}\n\nDeseja atualizar agora?")
            .setPositiveButton("Atualizar Agora") { _, _ ->
                downloadAndInstallOtaUpdate(info)
            }
            .setNegativeButton("Mais Tarde", null)
            .show()
    }

    private fun downloadAndInstallOtaUpdate(info: AutoUpdateManager.UpdateInfo) {
        val progressDialog = ProgressDialog(this).apply {
            setTitle("Baixando DTG Sync")
            setMessage("Transferindo atualização do servidor da DRONEPOL...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        val fullDownloadUrl = if (info.downloadUrl.startsWith("http")) {
            info.downloadUrl
        } else {
            "https://dtg.yuriodilon.com" + info.downloadUrl
        }

        lifecycleScope.launch {
            val downloadResult = autoUpdateManager.downloadApk(fullDownloadUrl) { progress ->
                lifecycleScope.launch(Dispatchers.Main) {
                    progressDialog.progress = progress
                }
            }

            progressDialog.dismiss()

            downloadResult.onSuccess { apkFile ->
                appendLog("[OTA] Download concluído! Disparando instalador...")
                autoUpdateManager.installApk(this@MainActivity, apkFile)
            }.onFailure { err ->
                appendLog("[OTA ERRO] Falha no download do APK: ${err.message}")
                Toast.makeText(this@MainActivity, "Erro ao baixar atualização.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==========================================
    // 3. SINCRONIZAÇÃO DE VOOS E TELEMETRIA
    // ==========================================
    private fun updateStatsUi() {
        binding.tvSyncedCount.text = syncManager.getSyncedCount().toString()
        binding.tvLastSyncTime.text = "Última sincronização: ${syncManager.getLastSyncTimeString()}"
    }

    private fun checkServerConnection() {
        binding.tvStatusBadge.text = "● TESTANDO..."
        binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.dtg_warning_amber))

        lifecycleScope.launch {
            val isReachable = NetworkUtils.isServerReachable()
            if (isReachable) {
                binding.tvStatusBadge.text = "● ONLINE (DTG HUB)"
                binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.dtg_success_green))
                appendLog("[REDE] Conexão com o servidor DTG Hub ativa.")
            } else {
                binding.tvStatusBadge.text = "○ DESCONECTADO"
                binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.dtg_danger_red))
                appendLog("[ALERTA] Servidor inacessível. Ative a VPN WireGuard ou conecte ao Wi-Fi.")
            }
        }
    }

    private fun triggerManualSync() {
        binding.btnSyncNow.isEnabled = false
        binding.btnSyncNow.text = "Sincronizando..."

        lifecycleScope.launch {
            try {
                syncManager.executeSync(this@MainActivity)
            } finally {
                withContext(Dispatchers.Main) {
                    binding.btnSyncNow.isEnabled = true
                    binding.btnSyncNow.text = "Sincronizar Voos"
                    updateStatsUi()
                }
            }
        }
    }

    private fun appendLog(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            val current = binding.tvLogs.text.toString()
            binding.tvLogs.text = "$current\n$message"
            binding.scrollLogs.post {
                binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    override fun onLogMessage(message: String) = appendLog(message)
    override fun onSyncProgress(current: Int, total: Int) = appendLog("[PROGRESSO] Enviando voo $current de $total...")
    override fun onSyncFinished(successCount: Int, failedCount: Int) {
        lifecycleScope.launch(Dispatchers.Main) {
            updateStatsUi()
            if (successCount > 0) {
                Toast.makeText(this@MainActivity, "$successCount voo(s) sincronizado(s) com a Central!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkAndRequestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                appendLog("[SISTEMA] Solicitando permissão de acesso irrestrito a arquivos...")
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            } else {
                appendLog("[SISTEMA] Permissão de acesso aos arquivos de voo confirmada.")
            }
        } else {
            val readPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            if (readPerm != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    STORAGE_PERMISSION_CODE
                )
            } else {
                appendLog("[SISTEMA] Permissão de armazenamento confirmada.")
            }
        }
    }
}
