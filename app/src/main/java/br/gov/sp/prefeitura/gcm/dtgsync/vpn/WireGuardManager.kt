package br.gov.sp.prefeitura.gcm.dtgsync.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gerenciador da VPN WireGuard Integrada (DRONEPOL - GCM SP)
 *
 * Utiliza o SDK oficial do WireGuard para criar um túnel a nível de sistema operacional (VpnService).
 * Benefício: Garante que TODO o rádio (DTG Sync, DJI Pilot 2, MQTT 1883 e RTMP 1935)
 * conecte automaticamente à rede corporativa (100.66.229.107) sem depender de apps de terceiros.
 */
class WireGuardManager(private val context: Context) {

    companion object {
        private const val TAG = "WireGuardManager"
        private const val PREFS_NAME = "dtg_wireguard_prefs"
        private const val KEY_PRIVATE_KEY = "wg_private_key"
        private const val KEY_PUBLIC_KEY = "wg_public_key"
        private const val KEY_SERVER_ENDPOINT = "wg_server_endpoint"
        private const val KEY_SERVER_PUBKEY = "wg_server_pubkey"
        private const val KEY_CLIENT_IP = "wg_client_ip"
        private const val KEY_AUTO_CONNECT = "wg_auto_connect"

        // Configurações padrão institucionais DRONEPOL
        const val DEFAULT_SERVER_ENDPOINT = "100.66.229.107:51820"
        const val DEFAULT_SERVER_PUBKEY = "BM9Y44p19m0H7GfQ617R0Wz8Vb9u56aJm2LkP8Q9x1Y="
        const val DEFAULT_ALLOWED_IPS = "100.64.0.0/10" // Split-tunneling (tráfego da rede GCM)
        const val DEFAULT_CLIENT_IP = "100.66.229.200/32"

        private var instance: WireGuardManager? = null

        fun getInstance(context: Context): WireGuardManager {
            return instance ?: synchronized(this) {
                instance ?: WireGuardManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var backend: Backend? = null
    private var activeTunnel: Tunnel? = null

    // Modelo de túnel da DRONEPOL
    private class DtgTunnel(private val name: String) : Tunnel {
        override fun getName(): String = name
        override fun onStateChange(newState: Tunnel.State) {
            Log.i(TAG, "Estado do Túnel $name alterado para: $newState")
        }
    }

    init {
        try {
            backend = GoBackend(context)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao inicializar GoBackend do WireGuard", e)
        }
        ensureKeypairGenerated()
    }

    /**
     * Garante a geração do par de chaves criptográficas do rádio
     */
    private fun ensureKeypairGenerated() {
        if (!prefs.contains(KEY_PRIVATE_KEY)) {
            val keyPair = KeyPair()
            prefs.edit()
                .putString(KEY_PRIVATE_KEY, keyPair.privateKey.toBase64())
                .putString(KEY_PUBLIC_KEY, keyPair.publicKey.toBase64())
                .apply()
            Log.i(TAG, "Novo par de chaves WireGuard gerado para o rádio: ${keyPair.publicKey.toBase64()}")
        }
    }

    fun getLocalPublicKey(): String {
        return prefs.getString(KEY_PUBLIC_KEY, "") ?: ""
    }

    fun getLocalPrivateKey(): String {
        return prefs.getString(KEY_PRIVATE_KEY, "") ?: ""
    }

    fun getServerEndpoint(): String {
        return prefs.getString(KEY_SERVER_ENDPOINT, DEFAULT_SERVER_ENDPOINT) ?: DEFAULT_SERVER_ENDPOINT
    }

    fun setServerEndpoint(endpoint: String) {
        prefs.edit().putString(KEY_SERVER_ENDPOINT, endpoint).apply()
    }

    fun isAutoConnectEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_CONNECT, true)
    }

    fun setAutoConnectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
    }

    /**
     * Verifica se o sistema Android requer permissão para iniciar a VPN
     */
    fun prepareVpnIntent(activity: Activity): Intent? {
        return VpnService.prepare(activity)
    }

    /**
     * Inicia o túnel WireGuard de sistema
     */
    suspend fun startTunnel(): Result<Unit> = withContext(Dispatchers.IO) {
        val b = backend ?: return@withContext Result.failure(IllegalStateException("Backend WireGuard indisponível"))

        try {
            val privateKey = Key.fromBase64(getLocalPrivateKey())
            val serverPublicKey = Key.fromBase64(prefs.getString(KEY_SERVER_PUBKEY, DEFAULT_SERVER_PUBKEY) ?: DEFAULT_SERVER_PUBKEY)
            val endpoint = InetEndpoint.parse(getServerEndpoint())

            val clientIp = InetNetwork.parse(prefs.getString(KEY_CLIENT_IP, DEFAULT_CLIENT_IP) ?: DEFAULT_CLIENT_IP)
            val allowedIps = InetNetwork.parse(DEFAULT_ALLOWED_IPS)

            // Constrói interface local do rádio
            val iface = Interface.Builder()
                .parsePrivateKey(privateKey.toBase64())
                .addAddress(clientIp)
                .addDnsServer(java.net.InetAddress.getByName("1.1.1.1"))
                .build()

            // Constrói peer do servidor DTG Hub
            val peer = Peer.Builder()
                .parsePublicKey(serverPublicKey.toBase64())
                .setEndpoint(endpoint)
                .addAllowedIp(allowedIps)
                .setPersistentKeepalive(25)
                .build()

            val config = Config.Builder()
                .setInterface(iface)
                .addPeer(peer)
                .build()

            val tunnel = DtgTunnel("DTG-VPN")
            b.setState(tunnel, Tunnel.State.UP, config)
            activeTunnel = tunnel

            Log.i(TAG, "Túnel WireGuard conectado com sucesso ao servidor: $endpoint")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao conectar túnel WireGuard", e)
            Result.failure(e)
        }
    }

    /**
     * Desconecta o túnel WireGuard
     */
    suspend fun stopTunnel(): Result<Unit> = withContext(Dispatchers.IO) {
        val b = backend ?: return@withContext Result.failure(IllegalStateException("Backend WireGuard indisponível"))
        val tunnel = activeTunnel ?: DtgTunnel("DTG-VPN")

        try {
            b.setState(tunnel, Tunnel.State.DOWN, null)
            activeTunnel = null
            Log.i(TAG, "Túnel WireGuard desconectado com sucesso.")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao desconectar túnel WireGuard", e)
            Result.failure(e)
        }
    }

    /**
     * Checa se a VPN está ativa
     */
    fun isTunnelActive(): Boolean {
        return try {
            val tunnel = activeTunnel ?: DtgTunnel("DTG-VPN")
            backend?.getState(tunnel) == Tunnel.State.UP
        } catch (e: Exception) {
            false
        }
    }
}
