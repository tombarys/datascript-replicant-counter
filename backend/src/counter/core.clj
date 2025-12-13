(ns counter.core
  (:require [org.httpkit.server :as http-kit]
            [reitit.ring :as ring]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [clojure.tools.logging :as log]
            [clojure.core.async :as async]
            [datalevin.core :as d])
  (:gen-class))

;; Konfigurace Datalevin - jen cesta k databázi.
(def db-path "/opt/counter-app/data/datalevin-db")

;; Schema pro Datalevin (mapa map).
(def schema
  {:counter/id {:db/valueType :db.type/keyword
                :db/unique :db.unique/identity
                :db/cardinality :db.cardinality/one}
   :counter/value {:db/valueType :db.type/long
                   :db/cardinality :db.cardinality/one}})

(defn init-db!
  "Inicializuje Datalevin DB a vrátí connection.
   Pokud neexistuje entita :main-counter, vytvoří ji s hodnotou 0."
  []
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

;; Connection atom - drží Datalevin connection po startu aplikace.
(def conn-atom (atom nil))

;; SSE broadcast channel (aktuálně nepoužitý; broadcast jde přímo do client chanů).
(defonce broadcast-chan (async/chan (async/sliding-buffer 100)))

;; SSE clients - množina core.async kanálů pro jednotlivé připojené klienty.
(defonce sse-clients (atom #{}))

(defn edn-response
  "Vytvoří Ring response s EDN tělem a základními CORS hlavičkami."
  [data]
  {:status 200
   :headers {"Content-Type" "application/edn"
             "Access-Control-Allow-Origin" "*"
             "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
             "Access-Control-Allow-Headers" "Content-Type"}
   :body (pr-str data)})

(defn pull-counter
  "Vrátí mapu entity `:counter/id :main-counter` z dodané databáze.
   Pokud entita neexistuje, vrací nil."
  [db]
  (d/pull db '[*] [:counter/id :main-counter]))

(defn counter-tx
  "Vrátí DataScript/Datalevin transakci popisující aktuální stav counteru.
   Výsledkem je vektor map, který může frontend rovnou `(d/transact!)`."
  [db]
  (if-let [entity (pull-counter db)]
    [(select-keys entity [:counter/id :counter/value])]
    []))

(defn counter-message
  "Zabalí současný stav counteru do mapy `{:type :tx :tx [...] :meta {...}}`.
   `meta` dovoluje přidat info o původu zprávy (HTTP, SSE listener, ...)."
  ([db]
   (counter-message db {:source :api
                        :timestamp (System/currentTimeMillis)}))
  ([db meta]
   {:type :tx
    :tx   (counter-tx db)
    :meta meta}))

;; API Handlers - vrací EDN
(defn get-counter
  "GET /api/counter - vrátí aktuální stav counteru zabalený jako `{:tx [...]}`."
  [_]
  (let [db (d/db @conn-atom)]
    (edn-response (counter-message db {:source :http/get
                                       :timestamp (System/currentTimeMillis)}))))

(defn update-counter
  "POST /api/counter - přijme EDN keyword operaci (:increment/:decrement/:reset),
   provede transakci a vrátí nové datomy."
  [request]
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
    
    ;; Vrať novou transakci
    (let [updated-db (d/db @conn-atom)]
      (edn-response (counter-message updated-db {:source :http/post
                                                 :operation operation
                                                 :timestamp (System/currentTimeMillis)})))))

;; CORS preflight
(defn options-handler
  "OPTIONS handler pro CORS preflight."
  [_]
  {:status 200
   :headers {"Access-Control-Allow-Origin" "*"
             "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
             "Access-Control-Allow-Headers" "Content-Type"}})

;; Debug endpoints
(defn debug-all
  "GET /api/debug - vrátí diagnostická data (všechny datomy, counter, schema)."
  [_]
  (let [db (d/db @conn-atom)]
    (edn-response {:all-datoms (d/q '[:find ?e ?a ?v :where [?e ?a ?v]] db)
                   :counter (d/pull db '[*] [:counter/id :main-counter])
                   :schema (d/q '[:find ?ident :where [?e :db/ident ?ident]] db)})))

(defn debug-set
  "POST /api/debug/set - nastaví counter na dodanou hodnotu (EDN číslo)."
  [request]
  (let [body (slurp (:body request))
        value (read-string body)]
    (d/transact! @conn-atom [{:counter/id :main-counter :counter/value value}])
    (let [db (d/db @conn-atom)]
      (edn-response {:success true
                     :new-value value
                     :message (counter-message db {:source :debug/set
                                                    :timestamp (System/currentTimeMillis)})}))))

(defn sse-handler
  "GET /api/events - Server-Sent Events stream pomocí http-kit async."
  [request]
  (http-kit/with-channel request channel
    (let [client-chan (async/chan 10)]
      
      (swap! sse-clients conj client-chan)
      (log/info "SSE client connected. Total clients:" (count @sse-clients))
      
      ;; Send SSE headers with retry comment as initial data
      (http-kit/send! channel
                      {:status 200
                       :headers {"Content-Type" "text/event-stream"
                                 "Cache-Control" "no-cache"
                                 "Connection" "keep-alive"
                                 "X-Accel-Buffering" "no"
                                 "Access-Control-Allow-Origin" "*"}
                       :body ": connected\n\n"}
                      false)
      
      ;; Background loop to forward events
      (async/go-loop []
        (when-let [event (async/<! client-chan)]
          (when (http-kit/open? channel)
            (http-kit/send! channel (str "data: " (pr-str event) "\n\n") false)
            (recur))))
      
      ;; Cleanup on close
      (http-kit/on-close channel
                         (fn [_]
                           (log/info "SSE client disconnected. Total clients:" (dec (count @sse-clients)))
                           (swap! sse-clients disj client-chan)
                           (async/close! client-chan))))))

;; Broadcast to all SSE clients
(defn broadcast!
  "Pošle event všem připojeným SSE klientům (best-effort)."
  [event]
  (log/debug "Broadcasting to" (count @sse-clients) "clients:" event)
  (doseq [client @sse-clients]
    (async/put! client event)))

;; Setup transaction listener
(defn setup-tx-listener!
  "Zaregistruje Datalevin listener, který při změně `:counter/value`
   odešle SSE zprávu `{:tx [...]}` všem klientům."
  [conn]
  (d/listen! conn :sse-broadcast
    (fn [tx-report]
      (let [tx-data (:tx-data tx-report)
            counter-changes (filter #(= :counter/value (:a %)) tx-data)]
        (when (seq counter-changes)
          (let [new-value (:v (first counter-changes))
                message (counter-message (:db-after tx-report)
                                         {:source :tx-listener
                                          :timestamp (System/currentTimeMillis)})]
            (log/info "Counter changed to:" new-value)
            (broadcast! message)))))))

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
(defn -main
  "Spustí HTTP server (Jetty), inicializuje Datalevin a SSE broadcast." 
  []
  (log/info "Initializing Datalevin database...")
  (reset! conn-atom (init-db!))
  
  (log/info "Setting up transaction listener for SSE...")
  (setup-tx-listener! @conn-atom)
  
  (log/info "Starting Counter App Server on port 3000")
  (http-kit/run-server (wrap-defaults app api-defaults) 
                       {:port 3000})
  
  (log/info "Server started successfully with SSE support"))
