# Migrace Datahike → Datalevin + SSE

Branch: `datalevin-backend`

## 📊 Přehled změn

### Backend

**Datahike 0.6.1610 → Datalevin 0.9.22**

| Aspekt | Datahike | Datalevin |
|--------|----------|-----------|
| **Dependency** | `io.replikativ/datahike` | `datalevin/datalevin` |
| **Schema formát** | Vektor map | Mapa map |
| **Konfigurace** | Mapa s `:store`, `:schema-flexibility` | Cesta k LMDB adresáři |
| **Inicializace** | `create-database` + `connect` | `get-conn` (vše v jednom) |
| **Transakce** | `d/transact` | `d/transact!` |
| **Listener** | `d/listen` | `d/listen!` |

### Frontend

**Polling → SSE (Server-Sent Events)**

| Aspekt | Polling | SSE |
|--------|---------|-----|
| **Update latence** | až 5 sekund | < 100ms |
| **Network traffic** | Request každých 5s | Pouze při změnách |
| **Škálovatelnost** | N × requests/5s | Jeden stream/klient |
| **Reconnect** | Manuální | Automatický |

## 🔧 Klíčové změny v kódu

### Backend: Schema definice

```clojure
;; Datahike (vektor map)
(def schema [{:db/ident :counter/id
              :db/valueType :db.type/keyword
              :db/unique :db.unique/identity
              :db/cardinality :db.cardinality/one}
             {:db/ident :counter/value
              :db/valueType :db.type/long
              :db/cardinality :db.cardinality/one}])

;; Datalevin (mapa map)
(def schema {:counter/id {:db/valueType :db.type/keyword
                          :db/unique :db.unique/identity
                          :db/cardinality :db.cardinality/one}
             :counter/value {:db/valueType :db.type/long
                            :db/cardinality :db.cardinality/one}})
```

### Backend: Konfigurace a připojení

```clojure
;; Datahike
(def cfg {:store {:backend :file :path "/opt/counter-app/data/datahike-db"}})
(d/create-database cfg)
(def conn (d/connect cfg))

;; Datalevin
(def db-path "/opt/counter-app/data/datalevin-db")
(def conn (d/get-conn db-path schema))
```

### Backend: Transakce a listener

```clojure
;; Datahike
(d/transact conn [{:counter/id :main-counter :counter/value new-value}])
(d/listen conn :key callback)

;; Datalevin
(d/transact! conn [{:counter/id :main-counter :counter/value new-value}])
(d/listen! conn :key callback)
```

### Frontend: Polling → SSE

```clojure
;; Polling (původní)
(defonce poll-interval (atom nil))
(defn start-polling! []
  (reset! poll-interval
    (js/setInterval fetch-counter! 5000)))

;; SSE (nové)
(require '[counter.core-sse :as sse]
         '[counter.sync :as sync])
(defn init []
  (sse/start-event-stream! #(sync/apply-server-message! conn %)))
```

## 🚀 Deployment

### Lokální vývoj

```bash
# Backend
cd backend
clj -M -m counter.core

# Frontend
cd frontend
npm install
npx shadow-cljs watch app
```

### Production build

```bash
# Backend uberjar
cd backend
clj -X:uberjar

# Frontend release
cd frontend
npx shadow-cljs release app
```

### Server (Linux)

**Systémové závislosti:**
```bash
apt-get install libgomp1
```

**Deploy:**
```bash
# Nahrát JAR na server
scp backend/counter-app.jar root@server:/opt/counter-app/

# Restartovat službu
ssh root@server "systemctl restart counter-app"
```

## 📈 Výhody Datalevin

1. **Performance**: LMDB je velmi rychlé (memory-mapped storage)
2. **Škálovatelnost**: Zvládne databáze větší než RAM
3. **Fulltext search**: Vestavěný search engine
4. **Jednodušší API**: Méně boilerplate kódu
5. **Babashka pod**: Možnost skriptování

## ⚠️ Důležité poznámky

### macOS Development

Na macOS ARM64 jsou problémy s nativními knihovnami. Řešení:
- Použít Linux server pro production
- Lokální development: Datascript (frontend only)
- Nebo použít Datalevin přes Babashka pod

### SSE vs Polling

SSE vyžaduje persistent HTTP connection. Výhody:
- ✅ Real-time updates
- ✅ Automatický reconnect
- ✅ Standardní EventSource API

Nevýhody:
- ⚠️ Nekompatibilní s některými proxy servery
- ⚠️ Fallback na polling může být užitečný

## 🔗 Odkazy

- [Datalevin GitHub](https://github.com/juji-io/datalevin)
- [Datalevin vs Datahike porovnání](https://www.libhunt.com/compare-datalevin-vs-datahike)
- [SSE Specification](https://html.spec.whatwg.org/multipage/server-sent-events.html)
