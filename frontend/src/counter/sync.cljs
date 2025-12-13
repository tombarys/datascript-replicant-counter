(ns counter.sync
  "Modul pro synchronizaci stavu do DataScript DB.
   
   Cíl: mít jedno místo, kde *chápeme* zprávy ze serveru a překládáme je
   na DataScript transakce. Díky tomu zbytek aplikace (UI, API) jen volá
   \"sync\" a nemusí řešit detaily.
   
   Tento modul neřeší HTTP ani SSE – pouze dostane mapu od serveru a
   promění ji na `(d/transact!)`."
  (:require [datascript.core :as d]))

(defn apply-tx!
  "Aplikuje transakci (vektor map/datomů) do DataScript DB.
   
   DataScript transakce je vektor, kde každý prvek popisuje změnu
   (nejčastěji mapa entity nebo datom `[:db/add ...]`)."
  [conn tx-data]
  (when (seq tx-data)
    (d/transact! conn tx-data)))

(defn apply-server-message!
  "Přečte zprávu z backendu a pokud obsahuje klíč `:tx`, aplikuje ho.
   
   Formát očekávané zprávy:
   ```clojure
   {:type :tx            ; volitelný, pro logování
    :tx   [...]          ; DataScript transakce
    :meta {:source :api}}
   ```
   Pokud `:tx` chybí, nic se neaplikuje (a vypíše se warning do konzole).
   Vrací původní zprávu (pro případné další zpracování)."
  [conn {:keys [tx] :as message}]
  (if (seq tx)
    (apply-tx! conn tx)
    (js/console.warn "Server message without :tx, ignoring" (pr-str message)))
  message)

(defn set-loading!
  "Nastaví (nebo zruší) loading indikátor v lokální DB.
   
   Je to čistě UI stav – server o něm nic neví."
  [conn loading?]
  (d/transact! conn [{:counter/id :main-counter
                      :counter/loading loading?}]))
