#!/usr/bin/env bb
;; Deploy script for DataScript Counter App
;; Usage: bb deploy.bb

(require '[babashka.process :as p]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(def config
  {:server "root@91.98.234.203"
   :backend-dir "/opt/counter-app"
   :frontend-dir "/var/www/counter-app"})

(defn run! [cmd & {:keys [dir] :or {dir "."}}]
  "Run command and throw on error"
  (let [result (p/shell {:dir dir :continue true} cmd)]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "Command failed: " cmd) result)))
    result))

(defn step [emoji msg]
  (println (str emoji " " msg "...")))

(defn success [msg]
  (println (str "✅ " msg)))

(defn ssh [cmd]
  "Execute command on remote server"
  (run! (str "ssh " (:server config) " '" cmd "'")))

(defn scp [local remote]
  "Copy file to remote server"
  (run! (str "scp " local " " (:server config) ":" remote)))

(defn deploy! []
  (println "🚀 Deploying DataScript Counter App")
  (println "====================================")
  
  ;; 1. Build Frontend
  (step "📦" "Building frontend")
  (run! "npm run build" :dir "frontend")
  (success "Frontend built")
  
  ;; 2. Build Backend
  (step "📦" "Building backend")
  (run! "clojure -X:uberjar" :dir "backend")
  (success "Backend built")
  
  ;; 3. Deploy Frontend
  (step "🌐" "Deploying frontend")
  (scp "frontend/public/js/main.js" 
       (str (:frontend-dir config) "/js/"))
  (scp "frontend/public/index.html" 
       (:frontend-dir config))
  (success "Frontend deployed")
  
  ;; 4. Deploy Backend
  (step "🖥️ " "Deploying backend")
  (ssh (str "mkdir -p " (:backend-dir config)))
  (scp "backend/counter-app.jar" 
       (str (:backend-dir config) "/"))
  (scp "backend/src/counter/core.clj" 
       (str (:backend-dir config) "/src/counter/"))
  (scp "backend/deps.edn" 
       (:backend-dir config))
  (scp "backend/debug.clj" 
       (:backend-dir config))
  (success "Backend deployed")
  
  ;; 5. Restart Backend
  (step "🔄" "Restarting backend service")
  (ssh "systemctl restart counter-app")
  (success "Backend restarted")
  
  (println)
  (println "🎉 Deployment complete!")
  (println "Frontend: http://91.98.234.203")
  (println "Backend API: http://91.98.234.203/api/counter"))

(try
  (deploy!)
  (System/exit 0)
  (catch Exception e
    (println "❌ Deployment failed:")
    (println (.getMessage e))
    (System/exit 1)))
