#!/bin/bash
# Script di test gRPC tra plant con grpcurl
# Assicurati che grpcurl sia installato e i plant siano in ascolto sulle rispettive porte

PLANT_HOST=localhost
PLANT1_PORT=50051
PLANT2_PORT=50052

# 1. Invia un ElectionMessage a plant1
echo "[Invio ElectionMessage a plant1]"
grpcurl -plaintext -d '{"senderId":"plant2","price":0.5,"requestId":"test-election"}' \
  $PLANT_HOST:$PLANT1_PORT plant.grpc.ElectionService/SendElectionMessage

echo
# 2. Notifica a plant2 che plant1 sta uscendo
echo "[Notifica uscita a plant2]"
grpcurl -plaintext -d '{"senderId":"plant1"}' \
  $PLANT_HOST:$PLANT2_PORT plant.grpc.ElectionService/NotifyPlantExit
