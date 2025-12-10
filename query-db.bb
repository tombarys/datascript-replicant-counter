#!/usr/bin/env bb
;; Remote database query tool with HTTP API
;; Usage: 
;;   bb query-db.bb                    # Show current state
;;   bb query-db.bb set 42             # Set counter to 42
;;   bb query-db.bb repl               # Interactive REPL (SSH)

(require '[babashka.http-client :as http]
         '[clojure.pprint :as pp]
         '[clojure.edn :as edn]
         '[babashka.process :as p])

(def config
  {:api "http://91.98.234.203/api"
   :server "root@91.98.234.203"
   :backend-dir "/opt/counter-app"})

(defn api-get 
  "GET request, parse EDN response"
  [path]
  (-> (http/get (str (:api config) path))
      :body
      edn/read-string))

(defn api-post 
  "POST request with EDN data"
  [path data]
  (-> (http/post (str (:api config) path)
                 {:body (pr-str data)
                  :headers {"Content-Type" "application/edn"}})
      :body
      edn/read-string))

(defn show-state []
  (println "🔍 Datahike Database State")
  (println "==========================\n")
  
  (let [debug (api-get "/debug")
        counter (:counter debug)]
    (println "📊 Counter:")
    (pp/pprint counter)
    
    (println "\n📋 Schema:")
    (pp/pprint (:schema debug))
    
    (println "\n💾 Total datoms:" (count (:all-datoms debug)))))

(defn set-value! [value]
  (println (str "Setting counter to " value "..."))
  (let [result (api-post "/debug/set" value)]
    (if (:success result)
      (do
        (println "✅ Success!")
        (println "New value:" (:new-value result)))
      (println "❌ Failed:" result))))

(defn remote-repl []
  (println "🔌 Connecting to remote Datahike REPL...")
  (p/shell (str "ssh -t " (:server config) 
                " 'cd " (:backend-dir config) 
                " && clojure -M -i debug.clj -r'")))

(defn show-help []
  (println "Usage:")
  (println "  bb query-db.bb             # Show database state")
  (println "  bb query-db.bb set 42      # Set counter to 42")
  (println "  bb query-db.bb repl        # Interactive REPL (SSH)")
  (println "\nExamples:")
  (println "  bb query-db.bb set 0       # Reset counter")
  (println "  bb query-db.bb set 100     # Set to 100"))

(defn -main [& args]
  (try
    (case (first args)
      "set" (if-let [value (parse-long (second args))]
              (set-value! value)
              (do (println "❌ Invalid value. Must be a number.")
                  (System/exit 1)))
      "repl" (remote-repl)
      "help" (show-help)
      nil (show-state)
      (do (println "❌ Unknown command:" (first args))
          (show-help)
          (System/exit 1)))
    (catch Exception e
      (println "❌ Error:" (.getMessage e))
      (System/exit 1))))

(apply -main *command-line-args*)
