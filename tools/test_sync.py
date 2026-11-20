#!/usr/bin/env python3
"""
DTG Sync - Script de Teste Operacional (Simulador de Rádio Controle)
DRONEPOL - GCM SP (Divisão de Tecnologias Geoespaciais)

Utiliza APENAS a biblioteca padrão do Python (sem necessidade de 'pip install requests').
Compatível nativamente com qualquer distribuição Linux, macOS e Orange Pi.
"""

import os
import sys
import uuid
import json
import hashlib
import urllib.request
import urllib.error
import urllib.parse

# IP do Servidor na VPN Tailscale (usado quando o rádio está em campo)
TAILSCALE_HOST = "http://100.66.229.107:8085"
# IP Local (usado quando o script é executado diretamente no servidor Orange Pi)
LOCAL_HOST = "http://127.0.0.1:8085"

# Credenciais oficiais do DJI Pilot 2 no DTG Hub
PILOT_USER = "pilot"
PILOT_PASS = "pilot123"

# Arquivo de voo padrão gravado pela DRONEPOL
SAMPLE_FILE = "/home/zeus/projetos/dtghub/captures/Oct-6th-2026-08-01AM-Flight-Airdata.csv"

def calculate_sha256(filepath):
    """Calcula a assinatura criptográfica SHA-256 para integridade do voo."""
    sha = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(8192):
            sha.update(chunk)
    return sha.hexdigest()

def detect_server_host():
    """Detecta automaticamente se estamos rodando localmente no servidor ou via Tailscale."""
    for host in [LOCAL_HOST, TAILSCALE_HOST]:
        try:
            req = urllib.request.Request(host, headers={"User-Agent": "DTG-Sync-Test/1.0"})
            with urllib.request.urlopen(req, timeout=1.5) as resp:
                if resp.status == 200:
                    return host
        except Exception:
            continue
    return TAILSCALE_HOST

def get_auth_token(server_host):
    """Autentica com as credenciais do rádio no DTG Hub e obtém o token JWT."""
    login_url = f"{server_host}/manage/api/v1/login"
    login_payload = json.dumps({
        "username": PILOT_USER,
        "password": PILOT_PASS,
        "flag": 2
    }).encode("utf-8")

    req = urllib.request.Request(
        login_url,
        data=login_payload,
        headers={
            "Content-Type": "application/json",
            "User-Agent": "DTG-Sync-Android/1.0 (GCM SP DRONEPOL)"
        }
    )
    try:
        with urllib.request.urlopen(req, timeout=4) as response:
            res_data = json.loads(response.read().decode("utf-8"))
            if res_data.get("code") == 0:
                token = res_data.get("data", {}).get("access_token")
                return token
    except Exception as e:
        print(f"      [AVISO] Falha ao autenticar no DTG Hub ({login_url}): {e}")
    return None

def build_multipart_body(fields, files, boundary):
    """Monta o corpo multipart/form-data usando bytes puros da biblioteca padrão."""
    body = bytearray()
    crlf = b"\r\n"

    # Adicionar campos de formulário (texto)
    for name, value in fields.items():
        body.extend(f"--{boundary}".encode("utf-8") + crlf)
        body.extend(f'Content-Disposition: form-data; name="{name}"'.encode("utf-8") + crlf)
        body.extend(crlf)
        body.extend(str(value).encode("utf-8") + crlf)

    # Adicionar arquivos binários
    for name, (filename, file_bytes) in files.items():
        body.extend(f"--{boundary}".encode("utf-8") + crlf)
        body.extend(f'Content-Disposition: form-data; name="{name}"; filename="{filename}"'.encode("utf-8") + crlf)
        body.extend(b"Content-Type: application/octet-stream" + crlf)
        body.extend(crlf)
        body.extend(file_bytes)
        body.extend(crlf)

    body.extend(f"--{boundary}--".encode("utf-8") + crlf)
    return bytes(body)

def test_sync(filepath, radio_serial="dronepol-rcplus-56", inspetoria="Inspetoria Regional Sé (IR-SE)"):
    if not os.path.exists(filepath):
        print(f"[ERRO] Arquivo de voo não encontrado no caminho: {filepath}")
        return False

    file_size_kb = os.path.getsize(filepath) / 1024
    file_hash = calculate_sha256(filepath)
    filename = os.path.basename(filepath)

    server_host = detect_server_host()
    upload_url = f"{server_host}/manage/api/v1/devices/flight-records/upload"

    print("=" * 68)
    print("      DTG SYNC • SIMULADOR DE TRANSMISSÃO DE VOO DA FROTA GCM SP")
    print("=" * 68)
    print(f"[*] Rádio Identificador : {radio_serial}")
    print(f"[*] Inspetoria Vinculada: {inspetoria}")
    print(f"[*] Arquivo de Voo      : {filename} ({file_size_kb:.1f} KB)")
    print(f"[*] Assinatura SHA-256  : {file_hash}")
    print(f"[*] Servidor Detectado  : {server_host}")
    print(f"[*] Endpoint de Upload  : {upload_url}")
    print("-" * 68)

    # 1. Teste de conectividade e autenticação com o DTG Hub
    print("[1/3] Verificando conectividade e autenticação segura no DTG Hub...")
    token = get_auth_token(server_host)
    if token:
        print(f"      Autenticação: OK! Sessão autorizada para rádio ({PILOT_USER}).")
    else:
        print("      [AVISO] Continuando em modo de autenticação direta...")

    # 2. Transmitir o arquivo de voo
    print("[2/3] Empacotando arquivo de voo com assinatura criptográfica...")
    with open(filepath, "rb") as f:
        file_content = f.read()

    boundary = f"----DTGFormBoundary{uuid.uuid4().hex}"
    fields = {
        "radio_serial": radio_serial,
        "inspetoria": inspetoria,
        "file_hash": file_hash,
        "original_filename": filename,
        "file_size": str(len(file_content))
    }
    files = {
        "flight_file": (filename, file_content)
    }

    body_bytes = build_multipart_body(fields, files, boundary)

    print("[3/3] Enviando payload via túnel seguro...")
    req = urllib.request.Request(
        upload_url,
        data=body_bytes,
        method="POST"
    )
    req.add_header("User-Agent", "DTG-Sync-Android/1.0 (GCM SP DRONEPOL)")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    req.add_header("X-Radio-Serial", radio_serial)
    req.add_header("X-Inspetoria", inspetoria)
    req.add_header("X-File-Hash", file_hash)
    if token:
        req.add_header("x-auth-token", token)

    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            status_code = response.status
            response_text = response.read().decode("utf-8", errors="replace")
            print(f"[+] SUCESSO: Arquivo recebido pelo servidor DTG Hub (HTTP {status_code})!")
            print(f"    Resposta: {response_text[:200]}")
            return True
    except urllib.error.HTTPError as e:
        response_text = e.read().decode("utf-8", errors="replace")
        print(f"[i] Resposta HTTP da API DTG Hub: Código {e.code}")
        print(f"    Payload retornado: {response_text[:200]}")
        return e.code in [200, 201]
    except Exception as e:
        print(f"[!] Falha na transmissão: {e}")
        return False

if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else SAMPLE_FILE
    test_sync(target)
