(ns counter.api
  "Modul pro komunikaci s backendem (HTTP transport).
   
   Zajišťuje:
   - odesílání požadavků (fetch)
   - serializaci/deserializaci EDN
   - základní error handling
   
   Nezná doménovou logiku ani DataScript."
  (:require [cljs.reader]))

(defn fetch-edn!
  "Provede HTTP request a očekává EDN response.
   
   Parametry:
   - url: string (endpoint)
   - opts: mapa možností
     - :method (GET/POST/...)
     - :body (EDN data k odeslání)
     - :on-ok (callback při úspěchu, dostane EDN data)
     - :on-err (callback při chybě, dostane error objekt)"
  [url & [{:keys [method body on-ok on-err]}]]
  (-> (js/fetch url
                (clj->js (cond-> {:headers {"Content-Type" "application/edn"}}
                           method (assoc :method method)
                           body   (assoc :body (pr-str body)))))
      (.then #(.text %))
      (.then (fn [s] 
               (try
                 (let [data (cljs.reader/read-string s)]
                   (when on-ok (on-ok data)))
                 (catch js/Error e
                   (js/console.error "EDN parse error:" e)
                   (when on-err (on-err e))))))
      (.catch (fn [e] 
                (js/console.error "Network error:" e)
                (when on-err (on-err e))))))
