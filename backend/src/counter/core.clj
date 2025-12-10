(ns counter.core
  (:require [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [clojure.tools.logging :as log]
            [datahike.api :as d])
  (:gen-class))

;; Konfigurace Datahike - perzistentní datalog databáze na disku
(def db-config {:store {:backend :file :path "/opt/counter-app/data/datahike-db"}})

(defn init-db! 
  "Inicializuje Datahike databázi, pokud ještě neexistuje.
   Vytvoří schema a počáteční counter entitu s hodnotou 0."
  []
  (when-not (d/database-exists? db-config)
    (d/create-database db-config)
    (let [conn (d/connect db-config)]
      (d/transact conn [{:db/ident :counter/id
                         :db/valueType :db.type/keyword
                         :db/unique :db.unique/identity
                         :db/cardinality :db.cardinality/one}
                        {:db/ident :counter/value
                         :db/valueType :db.type/long
                         :db/cardinality :db.cardinality/one}
                        {:counter/id :main-counter
                         :counter/value 0}]))))

;; Globální Datahike connection atom
(def conn-atom (atom nil))

(defn edn-response 
  "Helper pro vytvoření HTTP response s EDN obsahem a CORS headers."
  [data]
  {:status 200
   :headers {"Content-Type" "application/edn"
             "Access-Control-Allow-Origin" "*"
             "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
             "Access-Control-Allow-Headers" "Content-Type"}
   :body (pr-str data)})

(defn get-counter-datoms 
  "Vrací counter entitu jako kolekci datoms [attribute value].
   Používá datalog query pro získání entity ID a pak d/q pro extrakci datoms."
  []
  (let [db (d/db @conn-atom)
        eid (d/q '[:find ?e .
                   :where [?e :counter/id :main-counter]]
                 db)]
    (when eid
      (d/q '[:find ?a ?v
             :in $ ?e
             :where [?e ?a ?v]]
           db eid))))

(defn get-counter 
  "API handler - GET /api/counter
   Vrací aktuální stav counteru jako EDN {:datoms [[attr value] ...]}."
  [_]
  (edn-response {:datoms (get-counter-datoms)}))

(defn update-counter 
  "API handler - POST /api/counter
   Přijímá EDN keyword akci (:increment/:decrement/:reset).
   Provede transakci v Datahike a vrací nové datomy."
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
    (d/transact @conn-atom [{:counter/id :main-counter
                             :counter/value new-value}])
    (edn-response {:datoms (get-counter-datoms)})))

(defn options-handler 
  "CORS preflight handler - vrací allowed headers a methods."
  [_]
  {:status 200
   :headers {"Access-Control-Allow-Origin" "*"
             "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
             "Access-Control-Allow-Headers" "Content-Type"}})

(defn debug-all 
  "Debug endpoint - GET /api/debug
   Vrací kompletní dump databáze: všechny datomy, counter entitu a schema."
  [_]
  (let [db (d/db @conn-atom)]
    (edn-response {:all-datoms (d/q '[:find ?e ?a ?v :where [?e ?a ?v]] db)
                   :counter (d/pull db '[*] [:counter/id :main-counter])
                   :schema (d/q '[:find ?ident :where [?e :db/ident ?ident]] db)})))

(defn debug-set 
  "Debug endpoint - POST /api/debug/set
   Nastaví counter na konkrétní hodnotu (pro debugging/testing).
   Přijímá EDN number jako body."
  [request]
  (let [body (slurp (:body request))
        value (read-string body)]
    (d/transact @conn-atom [{:counter/id :main-counter :counter/value value}])
    (edn-response {:success true :new-value value :datoms (get-counter-datoms)})))

;; Reitit router - definuje API endpointy
(def app-routes
  (ring/router
   ["/api"
    ["/counter" {:get get-counter
                 :post update-counter
                 :options options-handler}]
    ["/debug" {:get debug-all}]
    ["/debug/set" {:post debug-set
                   :options options-handler}]]))

;; Ring aplikace - kombinuje router s default middleware
(def app
  (ring/ring-handler app-routes (ring/create-default-handler)))

(defn -main 
  "Hlavní vstupní bod aplikace.
   Inicializuje DB, připojí se a spustí Jetty server na portu 3000."
  []
  (log/info "Initializing Datahike database...")
  (init-db!)
  (log/info "Connecting to Datahike database...")
  (reset! conn-atom (d/connect db-config))
  (log/info "Starting Counter App Server on port 3000")
  (jetty/run-jetty (wrap-defaults app api-defaults) 
                   {:port 3000 
                    :join? false})
  (log/info "Server started successfully"))
