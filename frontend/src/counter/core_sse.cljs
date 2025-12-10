(ns counter.core-sse
  "SSE (Server-Sent Events) frontend implementace.
   
   POZNÁMKA: SSE nefunguje s current backend (Jetty).
   Pro produkční použití je třeba http-kit nebo WebSockets.
   
   Aktuálně se používá polling-based fallback v core.cljs."
  (:require [datascript.core :as d]
            [cljs.reader]))

;; SSE connection atom
(defonce event-source (atom nil))

(defn start-event-stream! 
  "Spustí SSE (Server-Sent Events) connection k backendu.
   
   Automaticky se reconnectuje při výpadku spojení.
   
   Parametry:
   - sync-fn: callback funkce pro synchronizaci datoms
   
   Příklad:
   (start-event-stream! sync-datoms!)"
  [sync-fn]
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
          (let [data (cljs.reader/read-string (.-data event))
                datoms (:datoms data)]
            (js/console.log "📡 SSE update:" (pr-str data))
            (sync-fn datoms))
          (catch js/Error e
            (js/console.error "SSE parse error:" e)))))
    (.addEventListener source "error"
      (fn [e]
        (js/console.error "❌ SSE error, reconnecting..." e)
        (.close source)
        (reset! event-source nil)
        ;; Auto-reconnect po 3s
        (js/setTimeout #(start-event-stream! sync-fn) 3000)))))

(defn stop-event-stream! 
  "Zastaví SSE connection a provede cleanup."
  []
  (when @event-source
    (js/console.log "🔌 Closing SSE connection")
    (.close @event-source)
    (reset! event-source nil)))

;; Příklad integrace do core.cljs:
;;
;; 1. Přidej do requires:
;;    [counter.core-sse :as sse]
;;
;; 2. Při inicializaci:
;;    (sse/start-event-stream! sync-datoms!)
;;
;; 3. Při cleanup:
;;    (sse/stop-event-stream!)
;;
;; 4. Hot reload hooks:
;;    (defn ^:dev/before-load stop-before-reload []
;;      (sse/stop-event-stream!))
;;    
;;    (defn ^:dev/after-load start-after-reload []
;;      (sse/start-event-stream! sync-datoms!))
