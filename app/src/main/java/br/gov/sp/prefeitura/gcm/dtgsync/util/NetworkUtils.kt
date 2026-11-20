package br.gov.sp.prefeitura.gcm.dtgsync.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Utilitários de Rede e Validação de Conexão Segura
 *
 * DRONEPOL - GCM SP (Divisão de Tecnologias Geoespaciais)
 * Responsável por testar se o rádio controle (DJI RC Plus) possui acesso à internet
 * e se o túnel VPN do Tailscale consegue alcançar o servidor DTG Hub no IP 100.66.229.107.
 */
object NetworkUtils {

    const val DTG_HUB_TAILSCALE_IP = "100.66.229.107"
    const val DTG_HUB_PUBLIC_HOST = "dtg.yuriodilon.com"
    const val DTG_HUB_DEFAULT_PORT = 8085 // Porta HTTP Nginx no servidor

    /**
     * Verifica se o dispositivo possui alguma conexão de rede ativa (Wi-Fi, Ethernet ou Celular).
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Testa via Socket TCP de baixa latência se o servidor DTG Hub está respondendo.
     * Testa primeiro o endpoint público Cloudflare e depois o IP direto da VPN.
     */
    suspend fun isServerReachable(timeoutMs: Int = 2000): Boolean = withContext(Dispatchers.IO) {
        // 1. Testa via domínio público seguro (Cloudflare Tunnel)
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(DTG_HUB_PUBLIC_HOST, 443), timeoutMs)
                return@withContext true
            }
        } catch (ignored: Exception) {}

        // 2. Testa via IP da VPN Tailscale / WireGuard
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(DTG_HUB_TAILSCALE_IP, DTG_HUB_DEFAULT_PORT), timeoutMs)
                return@withContext true
            }
        } catch (ignored: Exception) {}

        false
    }
}
