(ns counter.core
  (:require [datascript.core :as d]
            [replicant.dom :as r]
            [cljs.reader]))

(def schema {:counter/id {:db/unique :db.unique/identity}})
(defonce conn (d/create-conn schema))

(defn set-loading! [loading]
  (d/transact! conn [{:counter/id :main-counter :counter/loading loading}]))

(defn sync-datoms! [datoms]
  (doseq [[attr value] datoms]
    (when (#{:counter/value :counter/loading} attr)
      (d/transact! conn [{:counter/id :main-counter attr value}]))))

(defn fetch-counter! []
  (set-loading! true)
  (-> (js/fetch "/api/counter")
      (.then #(.text %))
      (.then (fn [edn-str]
               (let [data (cljs.reader/read-string edn-str)
                     datoms (:datoms data)]
                 (sync-datoms! datoms)
                 (set-loading! false))))
      (.catch #(do (js/console.error "Fetch error:" %) (set-loading! false)))))

(defn update-counter! [action]
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

;; Polling jako fallback (jednodušší a spolehlivější než SSE s Jetty)
(defonce poll-interval (atom nil))

(defn start-polling! []
  (when @poll-interval
    (js/clearInterval @poll-interval))
  
  (js/console.log "🔄 Starting polling (5s interval)...")
  (reset! poll-interval
    (js/setInterval
      (fn []
        (-> (js/fetch "/api/counter")
            (.then #(.text %))
            (.then (fn [edn-str]
                     (let [data (cljs.reader/read-string edn-str)
                           datoms (:datoms data)]
                       (sync-datoms! datoms))))
            (.catch #(js/console.error "Poll error:" %))))
      5000))) ;; Poll každých 5s

(defn stop-polling! []
  (when @poll-interval
    (js/clearInterval @poll-interval)
    (reset! poll-interval nil)))

(r/set-dispatch!
 (fn [event-data handler-data]
   (when (= :replicant.trigger/dom-event (:replicant/trigger event-data))
     (case (first handler-data)
       :increment (update-counter! :increment)
       :decrement (update-counter! :decrement)
       :reset (update-counter! :reset)
       (js/console.warn "Unknown action:" handler-data)))))

(defn query-counter [db]
  (d/q '[:find ?value ?loading
         :in $ ?id
         :where 
         [?e :counter/id ?id]
         [?e :counter/value ?value]
         [?e :counter/loading ?loading]]
       db :main-counter))

(defn render-counter [db]
  (let [result (query-counter db)
        [value loading] (first result)]
    [:div.counter
     [:h2 "DataScript rules"]
     [:div.counter-value (if loading "..." value)]
     [:div.counter-controls
      [:button {:on {:click [:decrement]} :disabled loading} "-"]
      [:button {:on {:click [:increment]} :disabled loading} "+"]
      [:button {:on {:click [:reset]} :disabled loading} "Reset"]]]))

(defn render-app [db]
  [:div
   [:h1 "📮 Inkrementátor"]
   [:p {:style {:color "#666"}} "Frontend: Replicant + DataScript + Auto-sync 🔄"]
   (render-counter db)])

(defonce renderer (atom nil))

(defn render! []
  (when-let [el (js/document.getElementById "app")]
    (reset! renderer (r/render el (render-app @conn) @renderer))))

(d/listen! conn :render (fn [_] (render!)))

(defn ^:export init []
  (js/console.log "🚀 Counter app with Replicant + DataScript + Auto-sync")
  (fetch-counter!)
  (start-polling!)
  (render!))

;; Cleanup při unmount/reload
(defn ^:export stop []
  (js/console.log "🛑 Stopping auto-sync")
  (stop-polling!))

;; Hot reload support
(defn ^:dev/before-load stop-before-reload []
  (stop-polling!))

(defn ^:dev/after-load start-after-reload []
  (start-polling!)
  (render!))
