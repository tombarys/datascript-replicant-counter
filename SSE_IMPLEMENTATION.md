# SSE (Server-Sent Events) Implementace

Tento adresář obsahuje SSE implementaci pro real-time synchronizaci.

## ✅ Status

SSE je v tomto projektu aktivně používané a funguje takto:

- Backend endpoint `GET /api/events` drží otevřený HTTP stream.
- Každá zpráva je EDN mapa poslaná jako `data: <edn>\n\n`, např.:

  ```clojure
  {:type :tx
   :tx   [{:counter/id :main-counter :counter/value 42}]
   :meta {:source :tx-listener :timestamp 1700000000000}}
  ```

- Frontend modul `frontend/src/counter/core_sse.cljs` zprávu přečte a předá
  ji do `counter.sync/apply-server-message!`, který provede `(d/transact!)`.

## 📁 Soubory

### Backend

- **`backend/src/counter/core.clj`** - SSE server implementace
  - `sse-handler` (`GET /api/events`)
  - `broadcast!` (rozposílání eventů připojeným klientům)
  - `setup-tx-listener!` (broadcast při změně `:counter/value`)

### Frontend

- **`frontend/src/counter/core_sse.cljs`** - SSE klient (EventSource wrapper)
  - přihlásí se k `/api/events`
  - převede `event.data` z textu na EDN mapu
  - zavolá callback dodaný z `core.cljs`
- **`frontend/src/counter/sync.cljs`** - překládá zprávy na DataScript transakce
- **`frontend/src/counter/api.cljs`** - univerzální HTTP fetch pro EDN odpovědi

## 🔧 Použití

### Integrace do projektu

**Backend:**

- SSE endpoint a tx listener jsou už integrované v `backend/src/counter/core.clj`.

**Frontend (`core.cljs`):**

```clojure
(ns counter.core
  (:require [counter.core-sse :as sse]
            [counter.sync :as sync]))

;; Při inicializaci:
(sse/start-event-stream! #(sync/apply-server-message! conn %))

;; Při cleanup:
(sse/stop-event-stream!)
```

> 🧭 **Shrnutí pro orientaci:** backend je „pravda“, posílá změny jako transakce
> (`:tx`). Frontend je jen replika – přijde zpráva, my ji `d/transact!` uložíme
> do DataScriptu a Replicant UI si vše načte z lokální DB.

## 📚 Další zdroje

- [SSE Specification](https://html.spec.whatwg.org/multipage/server-sent-events.html)
- [REALTIME.md](REALTIME.md) - Kompletní real-time sync guide
