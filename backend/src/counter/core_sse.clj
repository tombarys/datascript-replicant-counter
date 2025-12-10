(ns counter.core-sse
  "SSE (Server-Sent Events) implementace pro real-time sync.
   
   POZNÁMKA: Tato implementace nefunguje správně s Jetty serverem.
   Jetty má problémy s long-lived streaming connections.
   
   Pro funkční SSE použijte http-kit místo Jetty:
   https://http-kit.github.io/
   
   Nebo použijte WebSockets knihovnu jako Sente:
   https://github.com/ptaoussanis/sente"
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async])
  (:import [java.io OutputStreamWriter]))

;; SSE broadcast channel pro real-time updates
(defonce broadcast-chan (async/chan (async/sliding-buffer 100)))

;; Set připojených SSE klientů
(defonce sse-clients (atom #{}))

(defn sse-handler 
  "SSE (Server-Sent Events) endpoint handler.
   Vytvoří streaming connection pro real-time updates.
   
   Použití: Přidej do routeru jako GET endpoint
   ['/events' {:get sse-handler}]
   
   PROBLÉM: Jetty nepodporuje správně piped streams pro SSE.
   Frontend se připojí, ale data neprocházejí."
  [request]
  (let [client-chan (async/chan 10)
        out (java.io.PipedOutputStream.)
        in (java.io.PipedInputStream. out)
        writer (java.io.OutputStreamWriter. out "UTF-8")]
    (swap! sse-clients conj client-chan)
    (log/info "SSE client connected. Total clients:" (count @sse-clients))
    (async/thread
      (try
        (while true
          (when-let [event (async/<!! client-chan)]
            (.write writer (str "data: " (pr-str event) "\n\n"))
            (.flush writer)))
        (catch Exception e
          (log/warn "SSE write error:" (.getMessage e))
          (swap! sse-clients disj client-chan)
          (async/close! client-chan)
          (.close writer))))
    {:status 200
     :headers {"Content-Type" "text/event-stream;charset=UTF-8"
               "Cache-Control" "no-cache, no-store, must-revalidate"
               "Connection" "keep-alive"
               "X-Accel-Buffering" "no"
               "Access-Control-Allow-Origin" "*"}
     :body in}))

(defn broadcast! 
  "Broadcastuje event všem připojeným SSE klientům.
   
   Příklad použití:
   (broadcast! {:type :counter-update 
                :value 42
                :timestamp (System/currentTimeMillis)})"
  [event]
  (log/debug "Broadcasting to" (count @sse-clients) "clients:" event)
  (doseq [client @sse-clients]
    (async/put! client event)))

(defn setup-tx-listener! 
  "Nastaví Datahike transaction listener pro SSE broadcasting.
   
   Při každé změně counter hodnoty broadcastuje update všem SSE klientům.
   
   Použití:
   (setup-tx-listener! conn get-counter-datoms-fn)
   
   Parametry:
   - conn: Datahike connection
   - get-datoms-fn: funkce, která vrací aktuální datomy"
  [conn get-datoms-fn]
  (require '[datahike.api :as d])
  (d/listen conn :sse-broadcast
    (fn [tx-report]
      (let [tx-data (:tx-data tx-report)
            counter-changes (filter #(= :counter/value (:a %)) tx-data)]
        (when (seq counter-changes)
          (let [new-value (:v (first counter-changes))
                datoms (get-datoms-fn)]
            (log/info "Counter changed to:" new-value)
            (broadcast! {:type :counter-update
                        :value new-value
                        :datoms datoms
                        :timestamp (System/currentTimeMillis)})))))))

;; Příklad integrace do core.clj:
;;
;; 1. Přidej do requires:
;;    [counter.core-sse :as sse]
;;
;; 2. Přidej do routeru:
;;    ["/events" {:get sse/sse-handler}]
;;
;; 3. Při startu aplikace:
;;    (sse/setup-tx-listener! @conn-atom get-counter-datoms)
