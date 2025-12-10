#!/usr/bin/env bb
;; Debug REPL script pro Datahike databázi
;; Použití: ssh root@91.98.234.203 'cd /opt/counter-app && clojure -M -i debug.clj -r'

(require '[datahike.api :as d])

(def db-config {:store {:backend :file :path "/opt/counter-app/data/datahike-db"}})

(defn db []
  "Vrátí aktuální snapshot databáze"
  (d/db (d/connect db-config)))

(defn all-datoms []
  "Vrátí všechny datomy v databázi"
  (d/q '[:find ?e ?a ?v
         :where [?e ?a ?v]]
       (db)))

(defn query-counter []
  "Dotaz na counter entity"
  (d/q '[:find ?e ?id ?value
         :where 
         [?e :counter/id ?id]
         [?e :counter/value ?value]]
       (db)))

(defn schema []
  "Vrátí schema atributy"
  (d/q '[:find ?ident ?valueType ?cardinality ?unique
         :where 
         [?e :db/ident ?ident]
         [(get-else $ ?e :db/valueType :none) ?valueType]
         [(get-else $ ?e :db/cardinality :none) ?cardinality]
         [(get-else $ ?e :db/unique :none) ?unique]]
       (db)))

(defn entity [eid]
  "Vrátí celou entitu podle entity ID"
  (d/pull (db) '[*] eid))

(defn reset-counter! []
  "Reset counteru na 0"
  (let [conn (d/connect db-config)]
    (d/transact conn [{:counter/id :main-counter :counter/value 0}])
    (println "Counter reset to 0")))

(defn set-counter! [value]
  "Nastaví counter na konkrétní hodnotu"
  (let [conn (d/connect db-config)]
    (d/transact conn [{:counter/id :main-counter :counter/value value}])
    (println "Counter set to" value)))

;; Helper funkce pro výpis
(defn pp [data]
  "Pretty print"
  (clojure.pprint/pprint data))

;; Automaticky vytiskni základní info
(println "\n🔍 Datahike Debug REPL")
(println "=====================")
(println "\nDostupné funkce:")
(println "  (all-datoms)       - všechny datomy")
(println "  (query-counter)    - counter data")
(println "  (schema)           - schema atributy")
(println "  (entity eid)       - entita podle ID")
(println "  (reset-counter!)   - reset na 0")
(println "  (set-counter! n)   - nastav hodnotu")
(println "  (pp data)          - pretty print")
(println "\nAktuální stav:")
(pp (query-counter))
(println)
