# SSE (Server-Sent Events) Implementace

Tento adresář obsahuje SSE implementaci pro real-time synchronizaci.

## ⚠️ Status: Nefunkční s Jetty

SSE kód je **aktuálně nepoužitelný** s Jetty serverem kvůli problémům se streaming connections.

## 📁 Soubory

### Backend
- **`backend/src/counter/core_sse.clj`** - SSE server implementace
  - SSE endpoint handler
  - Broadcast funkce
  - Datahike transaction listener

### Frontend
- **`frontend/src/counter/core_sse.cljs`** - SSE client implementace
  - EventSource API wrapper
  - Auto-reconnect logika
  - EDN message parsing

## 🔧 Použití

### Integrace do projektu

**Backend (`core.clj`):**
```clojure
(ns counter.core
  (:require [counter.core-sse :as sse]))

;; Přidej do routeru:
["/events" {:get sse/sse-handler}]

;; Při startu:
(sse/setup-tx-listener! @conn-atom get-counter-datoms)
```

**Frontend (`core.cljs`):**
```clojure
(ns counter.core
  (:require [counter.core-sse :as sse]))

;; Při inicializaci:
(sse/start-event-stream! sync-datoms!)

;; Při cleanup:
(sse/stop-event-stream!)
```

## 🚀 Pro funkční SSE použij:

### 1. http-kit místo Jetty

```clojure
;; deps.edn
{:deps {http-kit/http-kit {:mvn/version "2.8.0"}}}

;; core.clj
(require '[org.httpkit.server :as http])

(defn -main []
  (http/run-server app {:port 3000}))
```

### 2. Sente (doporučeno pro production)

Sente automaticky fallbackuje na AJAX long-polling:

```clojure
;; deps.edn
{:deps {com.taoensso/sente {:mvn/version "1.19.2"}}}

;; Viz: https://github.com/ptaoussanis/sente
```

### 3. WebSockets

Pro full-duplex komunikaci:
- http-kit má zabudované WebSockets
- immutant.web také podporuje WebSockets

## 📊 Aktuální řešení: Polling

Projekt aktuálně používá **HTTP polling** (každých 5s) jako spolehlivý fallback.

Pro většinu use-cases je polling dostatečný:
- ✅ Jednoduchá implementace
- ✅ Funguje všude
- ✅ Spolehlivé
- ⚠️ Latence max 5s

## 📚 Další zdroje

- [SSE Specification](https://html.spec.whatwg.org/multipage/server-sent-events.html)
- [http-kit documentation](https://http-kit.github.io/)
- [Sente documentation](https://github.com/ptaoussanis/sente)
- [REALTIME.md](REALTIME.md) - Kompletní real-time sync guide
