(ns counter.core-sse
  "SSE (Server-Sent Events) frontend implementace.
   
   Připojuje se na backend endpoint `/api/events` pomocí browser API `EventSource`
   a poslouchá na stream událostí ve formátu `text/event-stream`.
   
   **Formát zprávy (payload)**
   Backend zapisuje eventy jako řádky `data: ...` ukončené prázdným řádkem.
   `event.data` je EDN string, který parsujeme na mapu. Konvence v projektu:
   ```clojure
   {:type :tx
    :tx   [...]
    :meta {:source :sse :timestamp 123}}
   ```
   Callback `on-message` dostane právě tuto mapu.
   
   **Chování a životní cyklus**
   - připojení drží otevřený HTTP stream (server → klient)
   - při chybě se připojení zavře, vymaže z atomu a po 3 s se zkusí znovu
   - `stop-event-stream!` se používá při unmount/hot-reload
   
   Pozn.: SSE je jednosměrné (server → client). Akce z klienta dál posíláme přes
   HTTP `fetch` (viz `counter.api`)."
  (:require [cljs.reader]))

;; Aktuální EventSource instance (nebo nil když není připojeno).
(defonce event-source (atom nil))

(defn start-event-stream! 
  "Spustí SSE (Server-Sent Events) připojení k backendu.
   
   Parametry:
   - `on-message` – funkce, která dostane celou mapu zprávy (např. `{:tx [...]}`)
     a rozhodne, co s ní dál (typicky zavolá `counter.sync/apply-server-message!`).
   
   Chování:
   - zavře případné staré připojení
   - vytvoří `EventSource`
   - napojí handlery `open`/`message`/`error`
   - při chybě zavře spojení a po 3 s zkusí znovu (reconnect)"
  [on-message]
  (when @event-source
    (.close @event-source))
  (js/console.log "🔌 Connecting to SSE stream...")
  (let [source (js/EventSource. "/api/events")]
    (reset! event-source source)
    (.addEventListener source "open"
      (fn [_]
        (js/console.log "✅ SSE connected")))
    (.addEventListener source "message"
      (fn [event]
        (try
          (let [data (cljs.reader/read-string (.-data event))]
            (js/console.log "📡 SSE update:" (pr-str data))
            (on-message data))
          (catch js/Error e
            (js/console.error "SSE parse error:" e)))))
    (.addEventListener source "error"
      (fn [e]
        (js/console.error "❌ SSE error, reconnecting..." e)
        (.close source)
        (reset! event-source nil)
        ;; Auto-reconnect po 3s
        (js/setTimeout #(start-event-stream! on-message) 3000)))))

(defn stop-event-stream! 
  "Zastaví SSE connection a provede cleanup."
  []
  (when @event-source
    (js/console.log "🔌 Closing SSE connection")
    (.close @event-source)
    (reset! event-source nil)))

;; Integrace je v `counter.core/init` + hot-reload hooky.
;;
;; API:
;; - (start-event-stream! sync-fn)
;; - (stop-event-stream!)
