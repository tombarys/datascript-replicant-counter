# Babashka Scripts

Tento projekt používá [Babashka](https://babashka.org/) pro deployment a debugging.

## Instalace Babashky

```bash
# macOS
brew install borkdude/brew/babashka

# Linux
bash <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)

# Windows
scoop install babashka
```

## Dostupné scripty

### `deploy.bb` - Deploy na server
```bash
bb deploy.bb
```

Provede:
1. Build frontendu (shadow-cljs)
2. Build backendu (uberjar)
3. Deploy přes SCP
4. Restart systemd služby

**Výhody oproti bash:**
- Lepší error handling
- Clojure syntax
- Cross-platform

### `query-db.bb` - Database debugging
```bash
# Zobrazit stav databáze
bb query-db.bb

# Změnit hodnotu counteru
bb query-db.bb set 42

# Interaktivní REPL (SSH)
bb query-db.bb repl

# Nápověda
bb query-db.bb help
```

**Výhody oproti bash:**
- Instant start (<100ms)
- HTTP API místo SSH (rychlejší)
- Native EDN support
- Pretty printing

## Proč Babashka?

### Performance
- **Bash + JVM Clojure**: ~5-10s start
- **Babashka**: ~0.1s start

### Unified Language
```clojure
;; Všechno je Clojure - backend, frontend, scripting
(require '[babashka.http-client :as http])

(-> (http/get "http://api.example.com/data")
    :body
    edn/read-string
    :counter/value)
```

### Native Tooling
- HTTP client zabudovaný
- EDN parsing native
- File system operations
- Process management

### Cross-platform
Stejný code na macOS, Linux, Windows - žádné bash quirks.

## Migration z bash

Pokud potřebuješ bash příkazy:

```clojure
(require '[babashka.process :as p])

;; Jednoduchý příkaz
(p/shell "ls -la")

;; S error handling
(let [result (p/shell {:continue true} "npm install")]
  (when-not (zero? (:exit result))
    (throw (ex-info "npm install failed" result))))

;; SSH
(p/shell "ssh user@host 'command'")
```

## Další možnosti

### Task runner (bb.edn)
Můžeš vytvořit `bb.edn` pro task definice:

```clojure
{:tasks
 {deploy {:doc "Deploy application"
          :task (load-file "deploy.bb")}
  
  query {:doc "Query database"
         :task (load-file "query-db.bb")}
  
  test {:doc "Run tests"
        :task (shell "clojure -X:test")}}}
```

Pak:
```bash
bb deploy   # místo bb deploy.bb
bb query    # místo bb query-db.bb
```

### Watch mode
```clojure
(require '[babashka.fs :as fs])

(fs/watch "src" 
  (fn [event] 
    (println "File changed:" (:path event))
    (p/shell "bb test")))
```

## Dokumentace

- [Babashka Book](https://book.babashka.org/)
- [API Reference](https://github.com/babashka/babashka/blob/master/doc/api.md)
- [Examples](https://github.com/babashka/babashka/tree/master/examples)
