# DataScript Counter App

Minimalistická ClojureScript aplikace demonstrující datalog-driven architektu s Replicant a DataScript.

## Architektura

```
Browser (Frontend)              Server (Backend)
==================              ================
DataScript (in-memory)    ←→    Datahike (on-disk)
    ↓                               ↓
Replicant UI              ←→    Ring API (EDN)
```

### Jak funguje databázová synchronizace

#### Backend - Datahike (perzistentní datalogová DB)
- **Uložení**: Datahike ukládá datomy na disk v `/opt/counter-app/data/datahike-db`
- **Formát**: Binární formát optimalizovaný pro datalog queries
- **Perzistence**: Data přežijí restart serveru

#### API Komunikace - EDN (ne JSON!)
Backend **neposílá JSON**, ale **EDN datomy**:

```clojure
;; GET /api/counter vrací:
{:datoms #{[:counter/id :main-counter] 
           [:counter/value 4]}}

;; Frontend posílá:
:increment  ; pure EDN keyword
```

**Proč EDN?**
- Nativní Clojure data structures
- Podporuje keywordy, symboly, sety
- Žádné serialize/deserialize overhead
- Type-safe (keywords zůstávají keywords)

#### Frontend - DataScript (in-memory datalog DB)
1. **Receive**: Frontend dostane EDN string přes HTTP
2. **Parse**: `cljs.reader/read-string` parsuje EDN → Clojure data
3. **Sync**: Datomy se aplikují do lokální DataScript DB:
   ```clojure
   (defn sync-datoms! [datoms]
     (doseq [[attr value] datoms]
       (d/transact! conn [{:counter/id :main-counter attr value}])))
   ```
4. **Query**: UI čte z DataScript pomocí datalog:
   ```clojure
   (d/q '[:find ?value ?loading
          :where 
          [?e :counter/id :main-counter]
          [?e :counter/value ?value]]
        @conn)
   ```
5. **Render**: Replicant automaticky re-renderuje při změně DB

### Data Flow

```
User Click
  ↓
Replicant dispatch [:increment]
  ↓
HTTP POST :increment (EDN)
  ↓
Backend: Datahike transact
  ↓
Response: {:datoms [...]} (EDN)
  ↓
Frontend: parse EDN
  ↓
DataScript transact
  ↓
DB listener triggers re-render
  ↓
Replicant updates DOM
```

## Struktura Projektu

```
datascript-counter-app/
├── backend/
│   ├── src/counter/core.clj       # Clojure backend (Ring + Datahike)
│   ├── deps.edn                   # Backend dependencies
│   └── debug.clj                  # REPL debugging helpers
├── frontend/
│   ├── src/counter/core.cljs      # ClojureScript frontend (Replicant + DataScript)
│   ├── shadow-cljs.edn            # Build configuration
│   ├── package.json               # npm dependencies (shadow-cljs only)
│   └── public/
│       ├── index.html             # HTML shell
│       └── js/main.js             # Compiled ClojureScript
├── deploy.bb                      # Babashka deployment script
├── query-db.bb                    # Babashka database debugging tool
├── README.md                      # This file
├── DEBUGGING.md                   # Debugging guide
├── BABASHKA.md                    # Babashka scripts documentation
└── CHANGELOG.md                   # Version history
```

## Tech Stack

### Frontend
- **DataScript** 1.5.1 - In-memory datalog database
- **Replicant** 2025.06.21 - Minimalistický React-free UI framework
- **Shadow-cljs** - ClojureScript build tool

### Backend
- **Datahike** 0.6.1610 - Perzistentní datalogová databáze
- **Ring** + **Reitit** - HTTP server a routing
- **Clojure** 1.11.1

### Scripting & Deployment
- **Babashka** - Fast Clojure scripting for deploy & debugging

## Development

### Backend
```bash
cd backend
clojure -M -m counter.core        # Run backend on port 3000
```

### Frontend
```bash
cd frontend
npm install
npm run build                     # Production build
npm run dev                       # Development with hot reload (port 8080)
```

## Deployment

Deploy celé aplikace na server pomocí Babashky:

```bash
bb deploy.bb
```

Deploy script:
1. Kompiluje backend do uberjaru
2. Builduje frontend
3. Nahraje na server přes scp
4. Restartuje backend service

## Debugging

### Rychlý přehled stavu databáze
```bash
bb query-db.bb                    # Zobrazí counter, schema, počet datoms
```

### Modifikace dat
```bash
bb query-db.bb set 42             # Nastav counter na 42
bb query-db.bb set 0              # Reset na 0
```

### Interaktivní REPL (SSH)
```bash
bb query-db.bb repl               # Pro pokročilé datalog queries
```

### HTTP Debug endpointy
```bash
# Kompletní DB dump
curl http://91.98.234.203/api/debug

# Nastav hodnotu přes API
curl -X POST -H "Content-Type: application/edn" \
  -d "100" http://91.98.234.203/api/debug/set
```

Více v [DEBUGGING.md](DEBUGGING.md)

## Server Setup

Backend běží na: `91.98.234.203:3000`
Frontend (nginx): `91.98.234.203:80`

### Backend lokace:
- `/opt/counter-app/` - backend jar + data
- `/opt/counter-app/data/` - Datahike perzistence

### Frontend lokace:
- `/var/www/counter-app/` - statické soubory

## Idiomatický Datalog Přístup

### Proč DataScript + Datahike?
- **Stejný query jazyk** na frontendu i backendu
- **Immutabilní data** - time-travel, undo/redo zadarmo
- **Datalog queries** místo imperative state management
- **Unifikovaná architektura** - není rozdíl mezi "client state" a "server state"

### Proč Babashka pro scripting?
- **Instant start** - bb scripts startují <100ms (vs JVM ~5s)
- **Unified language** - Clojure všude (backend, frontend, deploy, debug)
- **Native tooling** - HTTP client, EDN, file system - vše zabudované
- **Cross-platform** - stejné chování na macOS/Linux/Windows

### Výhody EDN přenosu:
```clojure
;; JSON musíš parsovat a konvertovat typy:
{\"counter/value\": 5}  → (get-in data [:counter :value])

;; EDN zachovává typy:
{:counter/value 5}      → (:counter/value data)
```

## Budoucí rozšíření

- [ ] Scittle build pro vložení do blogu
- [ ] LocalStorage persistence na frontendu
- [ ] Optimistické updates (update lokálně, sync na pozadí)
- [ ] DataScript subscription pattern pro komplexnější UI

## License

Public domain - use as you wish!
