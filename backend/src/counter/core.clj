(ns counter.core
  (:require [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [clojure.tools.logging :as log]
            [datahike.api :as d])
  (:gen-class))

;; Konfigurace Datahike
(def db-config {:store {:backend :file :path "/opt/counter-app/data/datahike-db"}})

;; Inicializace databáze
(defn init-db! []
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

;; Connection atom
(def conn-atom (atom nil))

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
    (d/transact @conn-atom [{:counter/id :main-counter
                             :counter/value new-value}])
    
    ;; Vrať nové datomy
    (edn-response {:datoms (get-counter-datoms)})))

;; CORS preflight
(defn options-handler [_]
  {:status 200
   :headers {"Access-Control-Allow-Origin" "*"
             "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
             "Access-Control-Allow-Headers" "Content-Type"}})

;; Router
(def app-routes
  (ring/router
   ["/api"
    ["/counter" {:get get-counter
                 :post update-counter
                 :options options-handler}]]))

;; Ring aplikace
(def app
  (ring/ring-handler app-routes (ring/create-default-handler)))

;; Hlavní funkce
(defn -main []
  (log/info "Initializing Datahike database...")
  (init-db!)
  
  (log/info "Connecting to Datahike database...")
  (reset! conn-atom (d/connect db-config))
  
  (log/info "Starting Counter App Server on port 3000")
  (jetty/run-jetty (wrap-defaults app api-defaults) 
                   {:port 3000 
                    :join? false})
  
  (log/info "Server started successfully"))
