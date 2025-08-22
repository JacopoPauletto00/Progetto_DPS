#!/bin/bash
# Script di test per le REST API dell'admin server

ADMIN_HOST=localhost
ADMIN_PORT=8080

# 1. Registra due plant
curl -s -X POST http://$ADMIN_HOST:$ADMIN_PORT/register \
  -H "Content-Type: application/json" \
  -d '{"id":"plant1","host":"localhost","port":50051}'
echo -e "\n[Registrato plant1]"

curl -s -X POST http://$ADMIN_HOST:$ADMIN_PORT/register \
  -H "Content-Type: application/json" \
  -d '{"id":"plant2","host":"localhost","port":50052}'
echo -e "\n[Registrato plant2]"

# 2. Lista plant
curl -s http://$ADMIN_HOST:$ADMIN_PORT/plants
echo -e "\n[Lista plant]"

# 3. Statistiche emissioni (tutto l'intervallo)
curl -s "http://$ADMIN_HOST:$ADMIN_PORT/stats?from=0&to=9999999999999"
echo -e "\n[Stats emissioni]"

# 4. Plant1 lascia la rete
curl -s -X POST http://$ADMIN_HOST:$ADMIN_PORT/leave \
  -H "Content-Type: application/json" \
  -d '{"id":"plant1"}'
echo -e "\n[Plant1 ha lasciato la rete]"

# 5. Lista plant aggiornata
curl -s http://$ADMIN_HOST:$ADMIN_PORT/plants
echo -e "\n[Lista plant aggiornata]"
