"""
Serveur HTTP local persistant pour les appels Sofascore — VERSION curl_cffi
(remplace la version Playwright, qui se faisait bloquer systématiquement par
la détection anti-headless de Cloudflare, quel que soit le délai d'attente).

POURQUOI CE RETOUR EN ARRIÈRE :
La version Playwright lançait de vrais navigateurs Chromium headless pour
imiter un humain. Problème : Cloudflare détecte très efficacement les
navigateurs automatisés headless (empreintes WebGL/canvas, timing de rendu,
et bien d'autres signaux que neutraliser navigator.webdriver ne suffit plus
à masquer aujourd'hui). Résultat observé : blocage à 100%, sur tous les
workers et tous les appels, sans aucune amélioration possible en rallongeant
le timeout — ce n'est pas un problème de vitesse, c'est un problème de
détection structurelle.

curl_cffi ne lance AUCUN navigateur : il imite uniquement l'empreinte TLS
(JA3/JA4) et les en-têtes HTTP d'un vrai Chrome (impersonate="chrome124"),
au niveau de la couche réseau. Cloudflare ne voit alors qu'une requête HTTP
avec la signature TLS exacte de Chrome — sans jamais avoir à résoudre de
challenge JavaScript, puisqu'il n'y a pas de moteur de rendu à fingerprinter.
C'est exactement ce que fait déjà sofascore_fetch.py (le script one-shot du
projet), qui continue de fonctionner aujourd'hui contre Sofascore.

ARCHITECTURE : identique à sofascore_fetch.py sur le fond (curl_cffi,
impersonate="chrome124"), mais PERSISTANT — chaque "worker" garde sa propre
Session curl_cffi ouverte (cookies conservés entre appels, pas de coût de
démarrage de processus à chaque requête), contrairement à un script relancé
à chaque fois. Le pool de workers + la file d'attente (job_queue) servent
uniquement à répartir les appels entre plusieurs Sessions en parallèle —
curl_cffi n'a pas la contrainte mono-thread de Playwright, donc cette file
sert seulement à limiter la concurrence, pas à respecter une exigence
technique de la bibliothèque.

Usage : python sofascore_server.py [port] [pool_size]
  (défauts : port=8787, pool_size=4)

Installation requise (une fois) :
  pip install curl_cffi
"""
import sys
import json
import queue
import threading
from concurrent.futures import Future
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

from curl_cffi import requests as curl_requests

# Sur Windows, la console utilise par défaut un encodage local (cp1252),
# jamais UTF-8 — un print() contenant un caractère hors de ce jeu (emoji,
# accents) lève sinon une UnicodeEncodeError non rattrapée qui peut tuer un
# thread worker silencieusement.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

SOFASCORE_BASE = "https://api.sofascore.com/api/v1"

# FIX : une navigation directe vers l'API (que ce soit en tapant l'URL dans
# la barre d'adresse d'un vrai navigateur, ou via page.goto() côté
# Playwright) est bloquée en 403 "challenge" — MÊME depuis un vrai
# navigateur humain, sans aucun script. Ce n'est donc ni un problème
# d'empreinte TLS ni un blocage d'IP : www.sofascore.com se charge très
# bien, seul l'accès À L'API en navigation directe est refusé.
#
# Cause probable : le site réel n'accède JAMAIS à son API par une
# navigation de page — son JavaScript fait des appels fetch()/XHR DEPUIS
# une page déjà chargée, avec une signature de requête différente d'une
# navigation directe : un en-tête Referer pointant vers le site, et des
# en-têtes Sec-Fetch-* (Sec-Fetch-Site, Sec-Fetch-Mode, Sec-Fetch-Dest) qui
# indiquent "sous-ressource demandée par du JS", pas "navigation vers un
# document". Cloudflare peut très bien être configuré pour exiger cette
# signature précise sur ce sous-domaine, indépendamment de qui envoie la
# requête.
#
# On ajoute donc ces en-têtes à la main pour imiter un vrai appel AJAX du
# site plutôt qu'une navigation directe.
API_HEADERS = {
    "Referer": "https://www.sofascore.com/",
    "Origin": "https://www.sofascore.com",
    "Accept": "application/json, text/plain, */*",
    "Sec-Fetch-Site": "same-site",
    "Sec-Fetch-Mode": "cors",
    "Sec-Fetch-Dest": "empty",
}

DEFAULT_PORT = 8787
DEFAULT_POOL_SIZE = 4
REQUEST_TIMEOUT_SECONDS = 20
# Version Chrome imitée par curl_cffi. "chrome" (sans numéro) suit toujours
# la DERNIÈRE empreinte connue de curl_cffi au lieu d'une version figée —
# curl_cffi n'ajoute une nouvelle empreinte que quand le vrai Chrome change
# la sienne, donc épingler une version précise (ex: "chrome124", sorti en
# avril 2024) finit tôt ou tard par être une empreinte "connue et datée"
# que Cloudflare peut cataloguer et bloquer spécifiquement — ce qui est
# exactement ce qui s'est produit ici (403 "challenge" systématique alors
# que "chrome124" fonctionnait quelques temps plus tôt). "chrome" reste
# toujours aligné sur la dernière version supportée par la bibliothèque.
IMPERSONATE_TARGET = "chrome"

# File d'attente partagée : chaque élément est (path, Future). queue.Queue
# est thread-safe, donc utilisable depuis n'importe quel thread HTTP entrant.
job_queue: "queue.Queue" = queue.Queue()


def worker_loop(worker_id: int):
    """Boucle exécutée sur un thread dédié : garde une unique Session
    curl_cffi ouverte (cookies persistés entre appels) et traite les jobs de
    la file indéfiniment."""

    session = curl_requests.Session(impersonate=IMPERSONATE_TARGET)
    print(f"Worker {worker_id} prêt (session curl_cffi ouverte).", flush=True)

    while True:
        path, future = job_queue.get()
        if path is None:  # signal d'arrêt
            break
        try:
            result = _fetch_one(session, path)
            future.set_result(result)
        except Exception as e:
            future.set_result({"status": -1, "body": "", "error": str(e)})
        finally:
            job_queue.task_done()


def _fetch_one(session, path: str) -> dict:
    url = SOFASCORE_BASE + path
    try:
        response = session.get(url, headers=API_HEADERS, timeout=REQUEST_TIMEOUT_SECONDS)
    except Exception as e:
        return {"status": -1, "body": "", "error": f"erreur réseau : {e}"}

    return {"status": response.status_code, "body": response.text}


def submit_job(path: str, timeout_seconds: float = 30.0) -> dict:
    """Appelé depuis n'importe quel thread HTTP entrant : dépose un job dans
    la file et attend son résultat via un Future."""
    future: Future = Future()
    job_queue.put((path, future))
    try:
        return future.result(timeout=timeout_seconds)
    except Exception as e:
        return {"status": -1, "body": "", "error": f"timeout ou erreur en attendant le worker : {e}"}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def do_GET(self):
        parsed = urlparse(self.path)

        if parsed.path == "/health":
            self._send_json(200, {"status": "OK"})
            return

        if parsed.path == "/fetch":
            qs = parse_qs(parsed.query)
            path = qs.get("path", [None])[0]
            if not path:
                self._send_json(400, {"error": "paramètre 'path' manquant"})
                return

            result = submit_job(path)
            self._send_json(200, result)
            return

        self._send_json(404, {"error": "endpoint inconnu"})

    def _send_json(self, http_code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(http_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (ConnectionAbortedError, ConnectionResetError, BrokenPipeError):
            # Le client (Java) a fermé/abandonné la connexion avant qu'on
            # ait fini d'écrire (timeout côté client par ex.) — rien à faire,
            # ce n'est pas une erreur serveur.
            pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PORT
    pool_size = int(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_POOL_SIZE

    print(f"Démarrage de {pool_size} worker(s) curl_cffi (impersonate={IMPERSONATE_TARGET})...", flush=True)
    workers = []
    for i in range(pool_size):
        t = threading.Thread(target=worker_loop, args=(i,), name=f"curlcffi-worker-{i}", daemon=True)
        t.start()
        workers.append(t)

    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"sofascore_server (curl_cffi, {pool_size} workers) prêt sur 127.0.0.1:{port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        for _ in workers:
            job_queue.put((None, Future()))  # signal d'arrêt pour chaque worker


if __name__ == "__main__":
    main()