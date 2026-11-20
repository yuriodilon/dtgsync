package br.gov.sp.prefeitura.gcm.dtgsync.util

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Utilitários de Segurança e Integridade (Cadeia de Custódia)
 *
 * DRONEPOL - GCM SP (Divisão de Tecnologias Geoespaciais)
 * Garante que cada arquivo de voo possua uma assinatura criptográfica SHA-256 única,
 * impedindo o reenvio desnecessário de voos já registrados na Central e assegurando
 * a integridade dos dados para fins periciais ou auditorias.
 */
object SecurityUtils {

    /**
     * Calcula o hash SHA-256 de um arquivo de voo (.txt, .dat, .csv).
     *
     * @param file Arquivo local gravado pelo DJI Pilot 2
     * @return String hexadecimal de 64 caracteres representando a assinatura do arquivo
     */
    fun calculateFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
