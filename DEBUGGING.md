# Debugging Datahike Database

## Quick Start s Babashkou

### Zobrazit stav databáze
```bash
bb query-db.bb
```

Výstup:
```
🔍 Datahike Database State
==========================

📊 Counter:
{:db/id 3, :counter/id :main-counter, :counter/value 777}

📋 Schema:
#{[:counter/id] [:counter/value]}

💾 Total datoms: 132
```

### Změnit hodnotu
```bash
bb query-db.bb set 42      # Nastav na 42
bb query-db.bb set 0       # Reset na 0
bb query-db.bb set 1000    # Nastav na 1000
```

### Interaktivní REPL (pokročilé)
```bash
bb query-db.bb repl
```

V REPL máš k dispozici:
```clojure
(all-datoms)        ;; Všechny datomy v DB
(query-counter)     ;; Counter data
(schema)            ;; Schema atributy
(entity 3)          ;; Entita s ID 3
(reset-counter!)    ;; Reset na 0
(set-counter! 42)   ;; Nastav hodnotu
(pp data)           ;; Pretty print
```

## Alternativně: HTTP Debug API

Babashka script používá HTTP endpointy, můžeš je volat i ručně:

## Alternativně: HTTP Debug API

Babashka script používá HTTP endpointy, můžeš je volat i ručně:

### Kompletní DB dump
```bash
curl http://91.98.234.203/api/debug | bb -e "(clojure.pprint/pprint (read-string (slurp *in*)))"
```

### Změnit hodnotu přes curl
```bash
curl -X POST -H "Content-Type: application/edn" \
  -d "100" http://91.98.234.203/api/debug/set
```

### Z Clojure REPL (lokální development)
```clojure
(require '[babashka.http-client :as http]
         '[clojure.edn :as edn])

;; Získat stav
(-> (http/get "http://91.98.234.203/api/debug")
    :body
    edn/read-string
    clojure.pprint/pprint)

;; Změnit hodnotu
(http/post "http://91.98.234.203/api/debug/set"
           {:body "42"
            :headers {"Content-Type" "application/edn"}})
```

## Přímý SSH přístup (pokročilé)

Když potřebuješ low-level přístup k databázi na serveru:

## Užitečné queries

```clojure
;; Celá entita
(d/pull @conn '[*] [:counter/id :main-counter])

;; Historie změn (pokud máš temporal)
(d/q '[:find ?tx ?v 
       :where 
       [?e :counter/id :main-counter ?tx]
       [?e :counter/value ?v ?tx]] 
     (d/history @conn))

;; Počet entit
(d/q '[:find (count ?e) :where [?e :counter/id]] @conn)

;; All attributes použité v DB
(d/q '[:find (distinct ?a) :where [_ ?a]] @conn)
```

## Tips

- **Backup DB**: `tar -czf datahike-backup.tar.gz data/datahike-db/`
- **Restore DB**: `tar -xzf datahike-backup.tar.gz`
- **DB size**: `du -sh data/datahike-db/`
- **Watch logs**: `ssh root@91.98.234.203 'journalctl -u counter-app -f'`
