(ns counter.core
  (:require [datascript.core :as d]
            [replicant.dom :as r]
            [counter.api :as api]
            [counter.sync :as sync]
            [counter.core-sse :as sse]))

;; Schema definuje strukturu dat v DataScript DB.
(def schema {:counter/id {:db/unique :db.unique/identity}})

;; Celkový tok dat (pro orientaci):
;; 1. UI vyvolá akci (klik) -> `update-counter!` -> HTTP POST na backend.
;; 2. Backend odpoví mapou `{:tx [...]}` -> `apply-message!` -> `d/transact!`.
;; 3. SSE (server push) posílá stejné mapy -> také končí v `apply-message!`.
;; 4. DataScript listener `render!` přemaluje UI.

;; Globální DataScript connection - jedna in-memory DB pro celý frontend.
(defonce conn (d/create-conn schema))

(defn- apply-message!
  "Helper: vezme mapu ze serveru a pošle ji do `counter.sync`.
   Díky tomu máme na jednom místě, že *každá* odpověď má mít klíč `:tx`."
  [message]
  (sync/apply-server-message! conn message))

(defn fetch-counter!
  "Načte aktuální stav counteru z backendu (HTTP GET).
   Backend vrací mapu `{:tx [...]}` – DataScript transakci.
   Tu aplikujeme do lokální DB a vypneme loading stav."
  []
  (sync/set-loading! conn true)
  (api/fetch-edn! "/api/counter"
                  {:on-ok (fn [message]
                            (apply-message! message)
                            (sync/set-loading! conn false))
                   :on-err (fn [err]
                             (js/console.error "Fetch error:" err)
                             (sync/set-loading! conn false))}))

(defn update-counter!
  "Pošle akci (:increment/:decrement/:reset) na backend (HTTP POST).
   Backend vrací opět mapu `{:tx [...]}`."
  [action]
  (sync/set-loading! conn true)
  (api/fetch-edn! "/api/counter"
                  {:method "POST"
                   :body action
                   :on-ok (fn [message]
                            (apply-message! message)
                            (sync/set-loading! conn false))
                   :on-err (fn [err]
                             (js/console.error "Update error:" err)
                             (sync/set-loading! conn false))}))

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
  (let [[value loading] (or (first (query-counter db))
                            [0 false])
        loading? (boolean loading)]
    [:div.counter
     [:h2 "DataScript + SSE real-time"]
     [:div.counter-value (if loading? "..." value)]
     [:div.counter-controls
      [:button {:on {:click [:decrement]} :disabled loading?} "-"]
      [:button {:on {:click [:increment]} :disabled loading?} "+"]
      [:button {:on {:click [:reset]} :disabled loading?} "Reset"]]]))

(defn render-app
  "Root komponenta - renderuje celou aplikaci."
  [db]
  [:div
   [:h1 "📮 Inkrementátor"]
   [:p {:style {:color "#666"}} "Frontend: Replicant + DataScript + SSE 🔄"]
   (render-counter db)])

;; Renderer atom - drží Replicant virtual DOM state (pro efektivní re-render).
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
   1. označí UI jako `loading`
   2. přes HTTP natáhne aktuální stav (přijde jako `{:tx [...]}` a uloží se do DB)
   3. nastartuje SSE stream (pro další změny)
   4. provede první render."
  []
  (js/console.log "🚀 Counter app with Replicant + DataScript + SSE")
  (fetch-counter!)
  (sse/start-event-stream! apply-message!)
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
  (sse/start-event-stream! apply-message!)
  (render!))
