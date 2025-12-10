# Real-time Database Synchronization

## Možnosti pro real-time sync

### 1. Server-Sent Events (SSE) ⭐ Doporučeno
**Výhody:**
- Jednoduchá implementace
- Native browser API
- Automatické reconnect
- HTTP/2 friendly

**Použití:**
- Notifikace o změnách DB
- Server → Client push
- Monitoring dashboardy

### 2. WebSockets
**Výhody:**
- Full duplex komunikace
- Nízká latence

**Nevýhody:**
- Složitější správa connectionů
- Problém s HTTP proxies
- Overkill pro jednosměrný sync

### 3. HTTP Long Polling
**Výhody:**
- Funguje všude
- Fallback pro SSE

**Nevýhody:**
- Vyšší latence
- Více overhead

### 4. Datahike Transaction Log Replication
**Výhody:**
- Professional řešení
- Offline-first
- Time-travel

**Nevýhody:**
- Komplexní setup
- Overkill pro malé projekty

## Implementace: SSE + Datahike Listeners

### Backend změny

Přidej SSE endpoint a transaction listener:

```clojure
(ns counter.core
  (:require [ring.adapter.jetty :as jetty]
            [datahike.api :as d]
            [clojure.core.async :as async]))

;; Channel pro broadcasting změn
(defonce broadcast-chan (async/chan (async/sliding-buffer 100)))

;; Listener na Datahike transakce
(defn setup-transaction-listener! [conn]
  (d/listen! conn :broadcast
    (fn [{:keys [tx-data]}]
      (let [counter-changes (filter #(= :counter/value (:a %)) tx-data)]
        (when (seq counter-changes)
          (let [new-value (-> counter-changes first :v)]
            (async/put! broadcast-chan {:counter/value new-value
                                        :timestamp (System/currentTimeMillis)})))))))

;; SSE endpoint
(defn sse-handler [request]
  {:status 200
   :headers {"Content-Type" "text/event-stream"
             "Cache-Control" "no-cache"
             "Connection" "keep-alive"
             "Access-Control-Allow-Origin" "*"}
   :body (async/go-loop []
           (when-let [event (async/<! broadcast-chan)]
             (async/>! (:async-channel request)
                      (str "data: " (pr-str event) "\n\n"))
             (recur)))})

;; Router s SSE
(def app-routes
  (ring/router
   ["/api"
    ["/counter" {:get get-counter
                 :post update-counter}]
    ["/events" {:get sse-handler}]]))
```

### Frontend změny

Subscribe na SSE stream:

```clojure
(ns counter.core
  (:require [datascript.core :as d]
            [replicant.dom :as r]
            [cljs.reader]))

;; SSE connection
(defonce event-source (atom nil))

(defn start-event-stream! []
  (when @event-source
    (.close @event-source))
  
  (let [source (js/EventSource. "/api/events")]
    (reset! event-source source)
    
    (.addEventListener source "message"
      (fn [event]
        (let [data (cljs.reader/read-string (.-data event))
              value (:counter/value data)]
          (js/console.log "SSE update:" value)
          (d/transact! conn [{:counter/id :main-counter 
                              :counter/value value}]))))
    
    (.addEventListener source "error"
      (fn [e]
        (js/console.error "SSE error:" e)
        ;; Auto-reconnect po 3s
        (js/setTimeout #(start-event-stream!) 3000)))))

(defn ^:export init []
  (js/console.log "Starting counter with real-time sync")
  (fetch-counter!)  ; Initial load
  (start-event-stream!)  ; Subscribe
  (render!))
```

## Alternativa: Polling s exponential backoff

Jednodušší varianta bez SSE:

```clojure
(defonce poll-interval (atom nil))

(defn start-polling! []
  (when @poll-interval
    (js/clearInterval @poll-interval))
  
  (reset! poll-interval
    (js/setInterval
      (fn []
        (-> (js/fetch "/api/counter")
            (.then #(.text %))
            (.then (fn [edn-str]
                     (let [data (cljs.reader/read-string edn-str)
                           datoms (:datoms data)]
                       (sync-datoms! datoms))))))
      5000))) ;; Poll každých 5s
```

## WebSocket varianta (pokročilé)

Pro full-duplex komunikaci:

### Backend (s [http-kit](https://http-kit.github.io/))

```clojure
(require '[org.httpkit.server :as http])

(def ws-clients (atom #{}))

(defn ws-handler [request]
  (http/with-channel request channel
    (swap! ws-clients conj channel)
    
    (http/on-close channel
      (fn [_]
        (swap! ws-clients disj channel)))
    
    (http/on-receive channel
      (fn [data]
        (let [msg (read-string data)]
          (handle-ws-message msg channel))))))

(defn broadcast-to-all! [data]
  (doseq [client @ws-clients]
    (http/send! client (pr-str data))))

;; V transaction listeneru:
(d/listen! conn :ws-broadcast
  (fn [{:keys [tx-data]}]
    (broadcast-to-all! {:type :counter-update
                        :datoms tx-data})))
```

### Frontend (WebSocket)

```clojure
(defonce ws (atom nil))

(defn connect-ws! []
  (let [socket (js/WebSocket. "ws://localhost:3000/ws")]
    (reset! ws socket)
    
    (set! (.-onmessage socket)
      (fn [event]
        (let [data (cljs.reader/read-string (.-data event))]
          (when (= :counter-update (:type data))
            (sync-datoms! (:datoms data))))))
    
    (set! (.-onopen socket)
      (fn [] (js/console.log "WebSocket connected")))
    
    (set! (.-onclose socket)
      (fn [] 
        (js/console.log "WebSocket closed, reconnecting...")
        (js/setTimeout connect-ws! 3000)))))
```

## Doporučení podle use-case

### Pro tento Counter projekt: **SSE**
- Jednosměrný sync (server → client)
- Jednoduchá implementace
- Reliable

### Pro chat/collaborative app: **WebSockets**
- Potřebuješ bidirectional
- Real-time interactions

### Pro dashboard/monitoring: **SSE nebo Polling**
- SSE preferovaný
- Polling jako fallback

### Pro production offline-first: **Datahike replication**
- Komplexní, ale robustní
- Podívej se na [replikativ](https://github.com/replikativ/replikativ)

## Libraries

- **SSE**: Native browser, žádná lib potřeba
- **WebSocket server**: [http-kit](https://http-kit.github.io/), [immutant](https://immutant.org/)
- **Full stack**: [Sente](https://github.com/ptaoussanis/sente) (SSE + WebSocket fallback)
- **Datalog sync**: [Replikativ](https://github.com/replikativ/replikativ), [XTDB](https://xtdb.com/)

## Příklad s Sente (battle-tested)

[Sente](https://github.com/ptaoussanis/sente) je populární Clojure/Script lib pro real-time:

```clojure
;; Backend
(require '[taoensso.sente :as sente]
         '[taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]])

(let [{:keys [ch-recv send-fn connected-uids ajax-post-fn ajax-get-or-ws-handshake-fn]}
      (sente/make-channel-socket-server! (get-sch-adapter) {})]
  
  (defonce channel-socket ch-recv)
  (defonce chsk-send! send-fn)
  (defonce connected-uids connected-uids))

;; Broadcast při změně
(d/listen! conn :sente-broadcast
  (fn [{:keys [tx-data]}]
    (doseq [uid (:any @connected-uids)]
      (chsk-send! uid [:counter/update {:datoms tx-data}]))))

;; Frontend
(require '[taoensso.sente :as sente])

(let [{:keys [chsk ch-recv send-fn]}
      (sente/make-channel-socket-client! "/chsk" {:type :auto})]
  
  (defonce chsk chsk)
  (defonce ch-chsk ch-recv)
  (defonce chsk-send! send-fn))

(defmulti event-handler :id)

(defmethod event-handler :counter/update
  [{:keys [?data]}]
  (sync-datoms! (:datoms ?data)))

(sente/start-client-chsk-router! ch-chsk event-handler)
```

Sente má auto-reconnect, heartbeat, a fallback na AJAX long-polling!
