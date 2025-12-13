(ns counter.core
  (:require [datascript.core :as d]
            [replicant.dom :as r]
            [cljs.reader]
            [counter.core-sse :as sse]))

;; Schema definuje strukturu dat v DataScript DB
(def schema {:counter/id {:db/unique :db.unique/identity}})

;; Globální DataScript connection - in-memory databáze
(defonce conn (d/create-conn schema))

(defn set-loading!
  "Nastaví loading stav v DataScript DB."
  [loading]
  (d/transact! conn [{:counter/id :main-counter :counter/loading loading}]))

(defn sync-datoms!
  "Synchronizuje datomy z backendu do lokální DataScript DB.
   Přijímá kolekci [attr value] párů a aplikuje je jako transakce."
  [datoms]
  (doseq [[attr value] datoms]
    (when (#{:counter/value :counter/loading} attr)
      (d/transact! conn [{:counter/id :main-counter attr value}]))))

(defn fetch-counter!
  "Načte aktuální stav counteru z backendu (HTTP GET).
   Parsuje EDN response a synchronizuje do DataScript."
  []
  (set-loading! true)
  (-> (js/fetch "/api/counter")
      (.then #(.text %))
      (.then (fn [edn-str]
               (let [data (cljs.reader/read-string edn-str)
                     datoms (:datoms data)]
                 (sync-datoms! datoms)
                 (set-loading! false))))
      (.catch #(do (js/console.error "Fetch error:" %) (set-loading! false)))))

(defn update-counter!
  "Pošle akci (:increment/:decrement/:reset) na backend (HTTP POST).
   Backend vrací nové datomy, které se synchronizují do DataScript."
  [action]
  (set-loading! true)
  (-> (js/fetch "/api/counter"
                #js {:method "POST"
                     :headers #js {"Content-Type" "application/edn"}
                     :body (pr-str action)})
      (.then #(.text %))
      (.then (fn [edn-str]
               (let [data (cljs.reader/read-string edn-str)
                     datoms (:datoms data)]
                 (sync-datoms! datoms)
                 (set-loading! false))))
      (.catch #(do (js/console.error "Update error:" %) (set-loading! false)))))

;; Replicant event dispatcher - mapuje DOM events na akce
(r/set-dispatch!
 (fn [event-data handler-data]
   (when (= :replicant.trigger/dom-event (:replicant/trigger event-data))
     (case (first handler-data)
       :increment (update-counter! :increment)
       :decrement (update-counter! :decrement)
       :reset (update-counter! :reset)
       (js/console.warn "Unknown action:" handler-data)))))

(defn query-counter
  "Datalog query - získá hodnotu a loading stav z DataScript DB."
  [db]
  (d/q '[:find ?value ?loading
         :in $ ?id
         :where
         [?e :counter/id ?id]
         [?e :counter/value ?value]
         [?e :counter/loading ?loading]]
       db :main-counter))

(defn render-counter
  "Renderuje counter UI komponentu (Hiccup syntax).
   Čte data z DB pomocí datalog query."
  [db]
  (let [result (query-counter db)
        [value loading] (first result)]
    [:div.counter
     [:h2 "DataScript + SSE real-time"]
     [:div.counter-value (if loading "..." value)]
     [:div.counter-controls
      [:button {:on {:click [:decrement]} :disabled loading} "-"]
      [:button {:on {:click [:increment]} :disabled loading} "+"]
      [:button {:on {:click [:reset]} :disabled loading} "Reset"]]]))

(defn render-app
  "Root komponenta - renderuje celou aplikaci."
  [db]
  [:div
   [:h1 "📮 Inkrementátor"]
   [:p {:style {:color "#666"}} "Frontend: Replicant + DataScript + SSE 🔄"]
   (render-counter db)])

;; Renderer atom - Replicant virtual DOM state
(defonce renderer (atom nil))

(defn render!
  "Vyvolá Replicant re-render. Volá se při každé změně DataScript DB."
  []
  (when-let [el (js/document.getElementById "app")]
    (reset! renderer (r/render el (render-app @conn) @renderer))))

;; DataScript listener - automaticky volá render! při každé transakci
(d/listen! conn :render (fn [_] (render!)))

(defn ^:export init
  "Inicializace aplikace - volá se při načtení stránky.
   Načte data, spustí SSE stream a provede první render."
  []
  (js/console.log "🚀 Counter app with Replicant + DataScript + SSE")
  (fetch-counter!)
  (sse/start-event-stream! sync-datoms!)
  (render!))

(defn ^:export stop
  "Cleanup funkce - zastaví SSE. Volá se při unmount."
  []
  (js/console.log "🛑 Stopping SSE")
  (sse/stop-event-stream!))

(defn ^:dev/before-load stop-before-reload
  "Shadow-cljs lifecycle hook - volá se před hot reload."
  []
  (sse/stop-event-stream!))

(defn ^:dev/after-load start-after-reload
  "Shadow-cljs lifecycle hook - volá se po hot reload."
  []
  (sse/start-event-stream! sync-datoms!)
  (render!))
