# Projekt byl reorganizován do monorepo struktury!

## ✅ Co bylo provedeno:

1. **Stažen backend kód** ze serveru do lokálního repo
2. **Vytvořena monorepo struktura**:
   ```
   datascript-counter-app/
   ├── backend/          # Clojure backend (Datahike)
   ├── frontend/         # ClojureScript frontend (DataScript + Replicant)
   ├── deploy.sh         # Deploy script
   └── README.md         # Komplexní dokumentace
   ```

3. **Vytvořena dokumentace** vysvětlující:
   - Jak funguje EDN komunikace mezi backendem a frontendem
   - Proč není JSON ale EDN
   - Jak se synchronizují datomy z Datahike do DataScript
   - Celý data flow od kliknutí po render

4. **Deploy script** - `./deploy.sh` pro jednoduché nasazení

## 📚 Důležité informace o databázové synchronizaci:

### Backend (Datahike):
- **Perzistence**: Data uložena na disku v `/opt/counter-app/data/`
- **Formát**: Binární optimalizovaný pro datalog
- **Přežije**: Restart serveru

### Transport (EDN):
```clojure
{:datoms #{[:counter/value 4]}}  ; NE JSON!
```
- **Proč EDN?** Zachovává Clojure typy (keywords, sets, atd.)
- **Výhoda**: Žádný type conversion overhead

### Frontend (DataScript):
1. HTTP GET `/api/counter` → EDN string
2. `cljs.reader/read-string` → parsování
3. `sync-datoms!` → transakce do DataScript
4. DataScript listener → Replicant re-render

## 🚀 Jak to použít:

```bash
# Build a deploy vše najednou:
./deploy.sh

# Nebo samostatně:
cd frontend && npm run build
cd backend && clojure -X:uberjar
```

## 📦 Přenos projektu jinam:

Teď už stačí:
```bash
git clone <repo>
cd datascript-counter-app
./deploy.sh  # nebo deploy na jiný server
```

Vše je v Gitu, nic není "skryto" na serveru!

## 🎯 Normální praxe:

ANO - toto je standardní přístup:
- ✅ Monorepo (backend + frontend spolu)
- ✅ Deploy skripty ve version control
- ✅ Dokumentace v README
- ✅ Vše verzovatelné

---

Více viz `README.md`
