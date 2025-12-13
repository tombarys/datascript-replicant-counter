# Architektura DataScript Counter App

## Přehled Systému

```mermaid
graph TB
    subgraph Browser["🌐 Browser (Frontend)"]
        UI[Replicant UI]
        DS[DataScript<br/>in-memory DB]
        API[counter.api<br/>HTTP client]
        SSE[counter.core-sse<br/>EventSource]
        SYNC[counter.sync<br/>Transaction handler]
    end
    
    subgraph Server["🖥️ Backend Server"]
        RING[Ring/Reitit<br/>HTTP API]
        DL[Datalevin<br/>on-disk DB]
        TX_LISTENER[Transaction<br/>Listener]
        BROADCAST[SSE Broadcast]
    end
    
    UI -->|dispatch action| API
    API -->|HTTP POST :increment| RING
    RING -->|d/transact!| DL
    DL -->|trigger| TX_LISTENER
    TX_LISTENER -->|broadcast event| BROADCAST
    BROADCAST -->|SSE stream| SSE
    SSE -->|on-message| SYNC
    SYNC -->|d/transact!| DS
    DS -->|db-listener| UI
    
    RING -->|HTTP response| API
    API -->|apply-message!| SYNC
    
    style Browser fill:#e1f5ff
    style Server fill:#fff4e1
```

## 1. Data Flow - User Initiated Action

```mermaid
sequenceDiagram
    participant User
    participant UI as Replicant UI
    participant API as counter.api
    participant Backend as Ring Handler
    participant DB as Datalevin
    participant Sync as counter.sync
    participant DS as DataScript
    
    User->>UI: Click "+1" button
    UI->>UI: dispatch [:increment]
    UI->>API: update-counter! :increment
    API->>API: set-loading! true
    
    API->>Backend: POST /api/counter<br/>{:body :increment}
    Backend->>DB: d/q [:find ?v ...]
    DB-->>Backend: current value
    Backend->>Backend: (inc current)
    Backend->>DB: d/transact! [{:counter/value 4}]
    
    Backend-->>API: {:type :tx<br/>:tx [{:counter/id :main-counter<br/>:counter/value 4}]<br/>:meta {:source :http/post}}
    
    API->>Sync: apply-server-message!
    Sync->>DS: d/transact! tx-data
    DS->>DS: trigger db-listener
    DS->>UI: render! @conn
    UI->>UI: re-render DOM
    API->>API: set-loading! false
    UI-->>User: zobrazí novou hodnotu
```

## 2. Real-time Synchronization via SSE

```mermaid
sequenceDiagram
    participant Client1 as Browser 1
    participant SSE1 as EventSource 1
    participant Backend as Backend
    participant Listener as TX Listener
    participant DB as Datalevin
    participant SSE_Broadcast as SSE Broadcast
    participant SSE2 as EventSource 2
    participant Client2 as Browser 2
    
    Note over Client1,Client2: Client 1 provede akci
    
    Client1->>Backend: POST /api/counter :increment
    Backend->>DB: d/transact! [{:counter/value 5}]
    DB->>Listener: tx-report event
    
    Listener->>Listener: filter counter-changes
    Listener->>Listener: counter-message db-after
    Listener->>SSE_Broadcast: broadcast! {:tx [...]}
    
    par Broadcast to all clients
        SSE_Broadcast->>SSE1: async/put! event
        SSE_Broadcast->>SSE2: async/put! event
    end
    
    SSE1->>Client1: data: {:type :tx, :tx [...]}
    SSE2->>Client2: data: {:type :tx, :tx [...]}
    
    Client1->>Client1: sync/apply-server-message!
    Client2->>Client2: sync/apply-server-message!
    
    Note over Client1,Client2: Oba klienti mají stejný stav
```

## 3. Frontend Initialization & Connection Setup

```mermaid
sequenceDiagram
    participant Page as HTML Page
    participant Core as counter.core
    participant SSE as core-sse
    participant Backend as /api/events
    participant API as counter.api
    participant Sync as counter.sync
    participant DS as DataScript
    
    Page->>Core: load script
    Core->>Core: create-conn schema
    Core->>Core: setup db-listener
    
    Core->>API: fetch-counter! (GET)
    API->>Backend: GET /api/counter
    Backend-->>API: {:tx [{:counter/id :main-counter<br/>:counter/value 4}]}
    API->>Sync: apply-server-message!
    Sync->>DS: d/transact! tx-data
    
    Core->>SSE: start-event-stream!
    SSE->>Backend: new EventSource("/api/events")
    Backend-->>SSE: 200 OK text/event-stream
    Backend-->>SSE: : connected\n\n
    SSE->>SSE: addEventListener "open"
    SSE->>SSE: addEventListener "message"
    
    Note over Core,DS: Frontend ready, listening for updates
```

## 4. EDN Communication Protocol

```mermaid
graph LR
    subgraph Frontend
        A[User Action] -->|keyword| B[API Module]
        B -->|pr-str| C[":increment"]
    end
    
    subgraph Transport
        C -->|HTTP POST| D[Network]
        D -->|text/plain| E[Backend]
    end
    
    subgraph Backend_Process[Backend]
        E -->|read-string| F[":increment keyword"]
        F -->|case| G[Logic]
        G -->|d/transact!| H[Datalevin]
        H -->|counter-message| I["{:type :tx<br/>:tx [...]<br/>:meta {...}}"]
        I -->|pr-str| J["String EDN"]
    end
    
    subgraph Response
        J -->|HTTP 200| K[Network]
        K -->|.text| L[Frontend]
        L -->|cljs.reader/read-string| M[Clojure Map]
        M -->|apply-message!| N[DataScript]
    end
    
    style Frontend fill:#e1f5ff
    style Backend_Process fill:#fff4e1
    style Transport fill:#f0f0f0
    style Response fill:#e8f5e9
```

## 5. Database Schema & Queries

```mermaid
graph TB
    subgraph Backend_Schema["Backend Schema (Datalevin)"]
        B1[":counter/id<br/>{:db/unique :db.unique/identity<br/>:db/valueType :db.type/keyword}"]
        B2[":counter/value<br/>{:db/valueType :db.type/long}"]
    end
    
    subgraph Frontend_Schema["Frontend Schema (DataScript)"]
        F1[":counter/id<br/>{:db/unique :db.unique/identity}"]
        F2[":counter/value<br/>(implicit)"]
        F3[":counter/loading<br/>(UI-only state)"]
    end
    
    subgraph Entity["Entity Example"]
        E["{:counter/id :main-counter<br/>:counter/value 4<br/>:counter/loading false}"]
    end
    
    subgraph Query["DataLog Query"]
        Q["[:find ?value ?loading<br/>:in $ ?id<br/>:where<br/>[?e :counter/id ?id]<br/>[?e :counter/value ?value]<br/>[?e :counter/loading ?loading]]"]
    end
    
    Backend_Schema -.->|sync via {:tx [...]}| Frontend_Schema
    Entity -.-> Backend_Schema
    Entity -.-> Frontend_Schema
    Frontend_Schema --> Query
    
    style Backend_Schema fill:#fff4e1
    style Frontend_Schema fill:#e1f5ff
```

## 6. Module Dependencies

```mermaid
graph TD
    subgraph Frontend_Modules["Frontend Modules"]
        CORE[counter.core<br/>🎯 Main orchestrator]
        API_MOD[counter.api<br/>📡 HTTP client]
        SYNC_MOD[counter.sync<br/>🔄 Transaction handler]
        SSE_MOD[counter.core-sse<br/>📻 EventSource]
    end
    
    subgraph External_Libs["External Libraries"]
        DS_LIB[datascript.core<br/>💾 In-memory DB]
        REP[replicant.dom<br/>🎨 Virtual DOM]
    end
    
    CORE --> API_MOD
    CORE --> SYNC_MOD
    CORE --> SSE_MOD
    CORE --> DS_LIB
    CORE --> REP
    
    API_MOD -.-> |cljs.reader| SYNC_MOD
    SSE_MOD -.-> |cljs.reader| SYNC_MOD
    SYNC_MOD --> DS_LIB
    
    style CORE fill:#4a90e2
    style API_MOD fill:#7ed321
    style SYNC_MOD fill:#f5a623
    style SSE_MOD fill:#bd10e0
```

## 7. Backend Handlers & Routing

```mermaid
graph TD
    REITIT[Reitit Router] --> ROUTES
    
    subgraph ROUTES["API Routes"]
        R1["/api/counter<br/>GET - get-counter<br/>POST - update-counter"]
        R2["/api/events<br/>GET - sse-handler"]
        R3["/api/debug<br/>GET - debug-all"]
        R4["/api/debug/set<br/>POST - debug-set"]
    end
    
    R1 --> |GET| H1[pull-counter db<br/>counter-message<br/>edn-response]
    R1 --> |POST| H2[read-string body<br/>case operation<br/>d/transact!<br/>edn-response]
    
    R2 --> H3[http-kit/with-channel<br/>async/chan<br/>setup listeners<br/>on-close cleanup]
    
    R3 --> H4[d/q all datoms<br/>d/pull counter<br/>edn-response]
    
    R4 --> H5[read-string value<br/>d/transact!<br/>edn-response]
    
    H2 --> DB[Datalevin]
    H4 --> DB
    H5 --> DB
    DB --> TXL[TX Listener]
    TXL --> BC[broadcast! to SSE clients]
    BC --> H3
    
    style REITIT fill:#4a90e2
    style DB fill:#f5a623
```

## 8. Error Handling & Reconnection

```mermaid
stateDiagram-v2
    [*] --> Disconnected
    
    Disconnected --> Connecting: start-event-stream!
    Connecting --> Connected: EventSource "open"
    Connected --> Receiving: listening for messages
    
    Receiving --> Processing: event.data received
    Processing --> Receiving: success
    Processing --> ParseError: cljs.reader/read-string fails
    ParseError --> Receiving: log error, continue
    
    Connected --> NetworkError: connection lost
    NetworkError --> Cleanup: close EventSource
    Cleanup --> Waiting: reset! event-source nil
    Waiting --> Connecting: setTimeout 3000ms
    
    Receiving --> Stopped: stop-event-stream!
    Connected --> Stopped: stop-event-stream!
    Stopped --> [*]
```

## 9. Transaction Lifecycle

```mermaid
flowchart TD
    START([User Click]) --> DISPATCH{Replicant<br/>Dispatch}
    DISPATCH --> ACTION[Action: :increment]
    ACTION --> LOADING1[Set loading: true]
    
    LOADING1 --> HTTP[HTTP POST<br/>/api/counter<br/>body: :increment]
    
    HTTP --> BACKEND_READ[Backend:<br/>d/q current value]
    BACKEND_READ --> CALC[Calculate:<br/>new-value = inc current]
    CALC --> BACKEND_TX[Backend:<br/>d/transact!]
    
    BACKEND_TX --> TRIGGER[Trigger TX Listener]
    TRIGGER --> BROADCAST[Broadcast SSE]
    
    BACKEND_TX --> RESPONSE[HTTP Response:<br/>{:tx [...]}]
    RESPONSE --> PARSE[Parse EDN]
    PARSE --> SYNC[counter.sync/<br/>apply-server-message!]
    
    SYNC --> DS_TX[DataScript:<br/>d/transact!]
    DS_TX --> LOADING2[Set loading: false]
    DS_TX --> LISTENER[DB Listener]
    LISTENER --> RENDER[Replicant render!]
    RENDER --> UI([UI Update])
    
    BROADCAST --> SSE_CLIENTS[Other SSE Clients]
    SSE_CLIENTS --> SYNC2[Their sync/<br/>apply-server-message!]
    SYNC2 --> DS_TX2[Their d/transact!]
    DS_TX2 --> RENDER2[Their UI update]
    
    style START fill:#4a90e2
    style UI fill:#7ed321
    style BACKEND_TX fill:#f5a623
    style BROADCAST fill:#bd10e0
```

## 10. Data Persistence

```mermaid
graph TB
    subgraph Memory["💾 In-Memory (Browser)"]
        DS[DataScript Connection<br/>defonce conn]
        ENTITY1["{:counter/id :main-counter<br/>:counter/value 4<br/>:counter/loading false}"]
    end
    
    subgraph Network["🌐 Network Transport"]
        EDN[EDN Messages<br/>"{:type :tx<br/>:tx [...]<br/>:meta {...}}"]
    end
    
    subgraph Disk["💿 On-Disk (Server)"]
        DL[Datalevin DB<br/>/opt/counter-app/data/datalevin-db]
        ENTITY2["{:counter/id :main-counter<br/>:counter/value 4}"]
    end
    
    DS -->|volatile| ENTITY1
    DL -->|persistent| ENTITY2
    
    ENTITY1 <-.->|HTTP/SSE| EDN
    EDN <-.->|HTTP/SSE| ENTITY2
    
    ENTITY1 -.->|lost on refresh| RELOAD([Page Reload])
    ENTITY2 -.->|survives| RESTART([Server Restart])
    
    style Memory fill:#e1f5ff
    style Network fill:#f0f0f0
    style Disk fill:#fff4e1
```

## Klíčové Principy

### 🔄 Synchronizace Stavu
- **Autoritativní zdroj**: Backend (Datalevin)
- **Replika**: Frontend (DataScript)
- **Protocol**: EDN přes HTTP/SSE
- **Consistency**: Eventual consistency via SSE broadcast

### 📡 Komunikační Kanály
1. **HTTP POST** - client → server akce
2. **HTTP GET** - initial state fetch
3. **SSE** - server → all clients broadcasts

### 🎯 Data Flow Pattern
```
UI Action → HTTP → Backend TX → DB → Listener → SSE Broadcast → All Clients
                          ↓
                    HTTP Response → Initiating Client
```

### 🧩 Separation of Concerns
- `counter.api` - transport layer (HTTP)
- `counter.sync` - synchronization logic
- `counter.core-sse` - SSE connection management
- `counter.core` - UI orchestration

### 💡 Why EDN?
- Native Clojure data structures
- Type preservation (keywords, symbols)
- No JSON serialization overhead
- Direct `read-string` / `pr-str`
