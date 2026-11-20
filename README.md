# DTG Sync • DRONEPOL (GCM SP)
### Agente Android de Sincronização Automática de Voos e Gestão de Frota Descentralizada

> **Divisão de Tecnologias Geoespaciais (DTG) • Guarda Civil Metropolitana de São Paulo**  
> Projeto Integrado ao **DTG Hub** (Base DJI Cloud API v1.10.0)

---

## 1. Visão Geral e Contexto Operacional

Na doutrina operacional da **DRONEPOL**, os drones da corporação (como a frota de **Matrice 4T**, DJI Mavic 3 Enterprise e rádios **DJI RC Plus**) são frequentemente descentralizados e cedidos para operações em diversas **Inspetorias Regionais** da capital paulista (ex: Inspetoria Sé, Ibirapuera, Santana, Mooca, Capela do Socorro, etc.).

### O Desafio
* Em muitas operações de rua, as equipes voam em locais com sinal de celular instável ou sem pontos de acesso móveis (hotspot 4G/5G). Exigir internet em tempo integral durante todos os voos é inviável na rotina policial.
* No entanto, a Central de Comando na DTG precisa auditar e consolidar **todas as horas voadas, rotas realizadas, altitudes atingidas e operadores responsáveis** de cada Inspetoria.

### A Solução: Arquitetura *Offline-First*
O **DTG Sync** é um aplicativo Android leve (instalado diretamente nos rádios controle DJI RC Plus) que opera no modelo **Offline-First** (idêntico à doutrina do *Airdata UAV*):
1. O policial realiza o voo operacional normalmente no campo, **100% offline**, sem internet. O aplicativo oficial **DJI Pilot 2** salva os registros brutos de voo na memória interna do rádio.
2. Quando a viatura retorna à base/Inspetoria e o rádio se conecta a qualquer rede Wi-Fi, o **DTG Sync desperta automaticamente em segundo plano**.
3. O aplicativo conecta-se à rede segura **Tailscale da GCM** (`100.66.229.107`), identifica os novos voos e envia-os com assinatura criptográfica para o servidor **DTG Hub**.
4. Minutos depois, o voo e sua trajetória já estão plotados no mapa tático da corporação, com horários exatos de decolagem e pouso.

---

## 2. Diagrama de Fluxo e Arquitetura

```
┌────────────────────────────────────────────────────────────────────────┐
│                   OPERAÇÃO POLICIAL EM CAMPO (OFFLINE)                 │
└────────────────────────────────────────────────────────────────────────┘
        │
        ▼ O policial opera o drone via DJI Pilot 2 (sem internet)
┌────────────────────────────────────────────────────────────────────────┐
│ Memória Interna do Rádio (DJI RC Plus - Android)                       │
│ Diretório: /sdcard/DJI/dji.pilot.2/FlightRecord/                       │
│ Arquivos: [2026-11-17_14-30-00_Flight.txt], [.dat], [.csv]             │
└────────────────────────────────────────────────────────────────────────┘
        │
        ▼ Viatura retorna à Inspetoria / Conecta ao Wi-Fi local
┌────────────────────────────────────────────────────────────────────────┐
│ Android OS (DJI RC Plus)                                               │
│ ├─► 1. "Always-on VPN" conecta instantaneamente ao Tailscale           │
│ │      (IP Rádio: 100.102.147.3 ➔ IP Servidor DTG: 100.66.229.107)    │
│ │                                                                      │
│ └─► 2. WorkManager dispara o "FlightSyncWorker" (DTG Sync)             │
│        ├─ Lê pasta de voos /sdcard/DJI/.../FlightRecord/               │
│        ├─ Gera hash SHA-256 de cada voo (evita duplicidade)            │
│        └─ Envia via HTTP POST Multipart com identificação da Inspetoria│
└────────────────────────────────────────────────────────────────────────┘
        │
        ▼ Túnel Criptografado VPN (Tailscale)
┌────────────────────────────────────────────────────────────────────────┐
│ Servidor DTG Hub (Central DRONEPOL SP - 100.66.229.107)                │
│ ├─ Endpoint: /manage/api/v1/devices/flight-records/upload              │
│ ├─ Valida integridade e grava no MySQL                                 │
│ └─ Plota no Mapa Tático: Trajeto, Horários, Piloto e Altitude          │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Principais Componentes do Projeto

| Arquivo / Classe | Função Técnica |
| :--- | :--- |
| [`MainActivity.kt`](file:///home/zeus/projetos/dtghub/dtgsync/app/src/main/java/br/gov/sp/prefeitura/gcm/dtgsync/MainActivity.kt) | Interface tática do operador: exibe status de conexão VPN com o DTG Hub, seletor de Inspetorias Regionais da GCM SP, contador de voos e terminal de logs em tempo real. |
| [`DtgSyncManager.kt`](file:///home/zeus/projetos/dtghub/dtgsync/app/src/main/java/br/gov/sp/prefeitura/gcm/dtgsync/service/DtgSyncManager.kt) | Núcleo de sincronização: localiza pastas do DJI Pilot 2, filtra arquivos pendentes usando tabela local de hashes SHA-256 e executa upload multipart com OkHttp. |
| [`FlightSyncWorker.kt`](file:///home/zeus/projetos/dtghub/dtgsync/app/src/main/java/br/gov/sp/prefeitura/gcm/dtgsync/worker/FlightSyncWorker.kt) | Tarefa resiliente em segundo plano gerenciada pelo `WorkManager` do Android. Acionada automaticamente com restrição de rede conectada. |
| [`BootReceiver.kt`](file:///home/zeus/projetos/dtghub/dtgsync/app/src/main/java/br/gov/sp/prefeitura/gcm/dtgsync/receiver/BootReceiver.kt) | `BroadcastReceiver` acionado no momento em que o rádio liga (`ACTION_BOOT_COMPLETED`), garantindo agendamento mesmo se ninguém abrir o app. |
| [`SecurityUtils.kt`](file:///home/zeus/projetos/dtghub/dtgsync/app/src/main/java/br/gov/sp/prefeitura/gcm/dtgsync/util/SecurityUtils.kt) | Cálculo de hash SHA-256 para integridade pericial (cadeia de custódia) e bloqueio de uploads redundantes. |
| [`NetworkUtils.kt`](file:///home/zeus/projetos/dtghub/dtgsync/app/src/main/java/br/gov/sp/prefeitura/gcm/dtgsync/util/NetworkUtils.kt) | Validação ativa de conectividade e teste de socket de baixa latência com o IP do servidor Tailscale (`100.66.229.107`). |

---

## 4. Diretórios de Voos Monitorados no Rádio

O aplicativo foi calibrado para varrer os caminhos padrão do **DJI Pilot 2**:
* `/sdcard/DJI/dji.pilot.2/FlightRecord/` (Padrão de registros de voo)
* `/sdcard/Android/data/dji.pilot.2/files/FlightRecord/` (Armazenamento com escopo Android 11+)
* `/sdcard/Android/data/dji.pilot.2/files/FlightRecord/MCDatFlightRecords/` (Caixa-preta de telemetria)
* `/sdcard/DJI/FlightRecord/` (Compatibilidade com versões legadas)

---

## 5. Por que NÃO necessita de Permissões ROOT?

* O Android dos rádios DJI (RC Plus / Smart Controller) armazena a pasta `/DJI/` no **armazenamento compartilhado** (*Shared External Storage*).
* O aplicativo solicita exclusivamente:
  * `MANAGE_EXTERNAL_STORAGE` (no Android 11+ via tela padrão do sistema).
  * `READ_EXTERNAL_STORAGE` (no Android 8 a 10).
* **Vantagem Operacional:** A instalação é 100% segura, não viola garantias da DJI e não requer procedimentos arriscados de desbloqueio de bootloader ou root.

---

## 6. Configuração de "VPN Sempre Ativa" (Always-On VPN)

Para que o rádio opere sem intervenção do operador em nenhuma Inspetoria:

1. **No Rádio DJI RC Plus:**
   * Acesse `Configurações do Android` ➔ `Rede e Internet` ➔ `VPN`.
   * Toque na engrenagem ⚙️ ao lado de **Tailscale**.
   * Ative a opção **VPN Sempre Ativa** (*Always-on VPN*).
   * Em `Bateria`, configure o Tailscale como **Sem Restrições**.
2. **No Painel Web do Tailscale (`login.tailscale.com`):**
   * Localize os rádios da frota (`dronepol-rcplus-56`, etc.).
   * Clique nos 3 pontinhos ➔ **Disable Key Expiry** (Desativar expiração de chave).
3. **Resultado:** Ligou o rádio na Inspetoria ➔ conecta na VPN sozinho ➔ DTG Sync envia os voos sozinho.

---

## 7. Como Compilar o Projeto

### A. Compilação Automática no GitHub (CI/CD)
O repositório possui uma pipeline automatizada em `.github/workflows/build-apk.yml`.
* A cada novo push ou criação de tag de versão (ex: `v1.1.0-wireguard`), o **GitHub Actions** compila o APK e disponibiliza o download direto na aba **Releases** do repositório:
  `https://github.com/yuriodilon/dtgsync/releases`

### B. Compilação Local no Android Studio
1. Abra o **Android Studio** (Hedgehog ou superior).
2. Selecione **File ➔ Open** e selecione a pasta do projeto: `/home/zeus/projetos/dtgsync`.
3. Aguarde o Gradle sincronizar as dependências (`WireGuard Tunnel`, `WorkManager`, `OkHttp`, `Material Components`).
4. Gere o APK em **Build ➔ Build Bundle(s) / APK(s) ➔ Build APK(s)** ou execute diretamente no rádio conectado via USB.

---

## 8. Atualização Remota da Frota (Auto-Update OTA)

O **DTG Sync v1.1.0** possui suporte nativo a atualização sem fio (OTA):
1. Quando uma nova versão do APK for publicada no **DTG Hub**, o aplicativo detecta automaticamente ao inicializar.
2. O operador também pode clicar em **"Verificar Atualizações"** na tela principal.
3. O download é realizado em segundo plano com barra de progresso e o instalador de pacotes do Android é acionado automaticamente.

---

## 9. Ferramenta de Teste e Simulação no Terminal

Para testar o envio de voos diretamente do terminal:

```bash
cd /home/zeus/projetos/dtgsync
python3 tools/test_sync.py
```
