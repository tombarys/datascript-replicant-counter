(ns counter.core
  (:require [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [clojure.tools.logging :as log]
            [clojure.core.async :as async]
            [datalevin.core :as d])
  (:import [java.io OutputStreamWriter])
  (:gen-class))

;; Konfigurace Datalevin - jen cesta k databázi
(def db-path "/opt/counter-app/data/datalevin-db")

;; Schema pro Datalevin (mapa map)
(def schema
  {:counter/id {:db/valueType :db.type/keyword
                :db/unique :db.unique/identity
                :db/cardinality :db.cardinality/one}
   :counter/value {:db/valueType :db.type/long
                   :db/cardinality :db.cardinality/one}})

;; Inicializace databáze
(defn init-db! []
  ;; get-conn vytvoří databázi automaticky, pokud neexistuje
  (let [conn (d/get-conn db-path schema)]
    ;; Zkontroluj, jestli už existuje counter, pokud ne, vytvoř ho
    (let [db (d/db conn)
          counter-exists? (d/q '[:find ?e .
                                 :where [?e :counter/id :main-counter]]
                               db)]
      (when-not counter-exists?
        (d/transact! conn [{:counter/id :main-counter
                           :counter/value 0}])
        (log/info "Initialized counter with value 0")))
    conn))

;; Connection atom
(def conn-atom (atom nil))

;; SSE broadcast channel
(defonce broadcast-chan (async/chan (async/sliding-buffer 100)))

;; SSE clients
(defonce sse-clients (atom #{}))

;; EDN response helper
(defn edn-response [data]
  {:status 200
   :headers {"Content-Type" "application/edn"
             "Access-Control-Allow-Origin" "*"
             "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
             "Access-Control-Allow-Headers" "Content-Type"}
   :body (pr-str data)})

;; Získej entitu jako datomy
(defn get-counter-datoms []
  (let [db (d/db @conn-atom)
        eid (d/q '[:find ?e .
                   :where [?e :counter/id :main-counter]]
                 db)]
    (when eid
      (d/q '[:find ?a ?v
             :in $ ?e
             :where [?e ?a ?v]]
           db eid))))

;; API Handlers - vrací EDN
(defn get-counter [_]
  (edn-response {:datoms (get-counter-datoms)}))

(defn update-counter [request]
  (let [body (slurp (:body request))
        operation (read-string body)
        db (d/db @conn-atom)
        current (d/q '[:find ?v .
                       :where [?e :counter/id :main-counter]
                              [?e :counter/value ?v]]
                     db)
        new-value (case operation
                    :increment (inc (or current 0))
                    :decrement (dec (or current 0))
                    :reset 0
                    current)]
    
    ;; Transakce
    (d/transact! @conn-atom [{:counter/id :main-counter
                             :counter/value new-value}])
    
    ;; Vrať nové datomy
    (edn-response {:datoms (get-counter-datoms)})))

;; CORS preflight
(defn options-handler [_]
  {:status 200
   :headers {"Access-Control-Allow-Origin" "*"
             "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
             "Access-Control-Allow-Headers" "Content-Type"}})

;; Debug endpoints
(defn debug-all [_]
  (let [db (d/db @conn-atom)]
    (edn-response {:all-datoms (d/q '[:find ?e ?a ?v :where [?e ?a ?v]] db)
                   :counter (d/pull db '[*] [:counter/id :main-counter])
                   :schema (d/q '[:find ?ident :where [?e :db/ident ?ident]] db)})))

(defn debug-set [request]
  (let [body (slurp (:body request))
        value (read-string body)]
    (d/transact! @conn-atom [{:counter/id :main-counter :counter/value value}])
    (edn-response {:success true :new-value value :datoms (get-counter-datoms)})))

;; SSE Handler with piped output stream
(defn sse-handler [request]
  (let [client-chan (async/chan 10)
        out (java.io.PipedOutputStream.)
        in (java.io.PipedInputStream. out)
        writer (java.io.OutputStreamWriter. out "UTF-8")]
    
    (swap! sse-clients conj client-chan)
    (log/info "SSE client connected. Total clients:" (count @sse-clients))
    
    ;; Background thread to write events
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

;; Broadcast to all SSE clients
(defn broadcast! [event]
  (log/debug "Broadcasting to" (count @sse-clients) "clients:" event)
  (doseq [client @sse-clients]
    (async/put! client event)))

;; Setup transaction listener
(defn setup-tx-listener! [conn]
  (d/listen! conn :sse-broadcast
    (fn [tx-report]
      (let [tx-data (:tx-data tx-report)
            counter-changes (filter #(= :counter/value (:a %)) tx-data)]
        (when (seq counter-changes)
          (let [new-value (:v (first counter-changes))
                datoms (get-counter-datoms)]
            (log/info "Counter changed to:" new-value)
            (broadcast! {:type :counter-update
                        :value new-value
                        :datoms datoms
                        :timestamp (System/currentTimeMillis)})))))))

;; Router
(def app-routes
  (ring/router
   ["/api"
    ["/counter" {:get get-counter
                 :post update-counter
                 :options options-handler}]
    ["/events" {:get sse-handler}]
    ["/debug" {:get debug-all}]
    ["/debug/set" {:post debug-set
                   :options options-handler}]]))

;; Ring aplikace
(def app
  (ring/ring-handler app-routes (ring/create-default-handler)))

;; Hlavní funkce
(defn -main []
  (log/info "Initializing Datalevin database...")
  (reset! conn-atom (init-db!))
  
  (log/info "Setting up transaction listener for SSE...")
  (setup-tx-listener! @conn-atom)
  
  (log/info "Starting Counter App Server on port 3000")
  (jetty/run-jetty (wrap-defaults app api-defaults) 
                   {:port 3000 
                    :join? false})
  
  (log/info "Server started successfully with SSE support"))
