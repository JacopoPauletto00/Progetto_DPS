#!/bin/bash
# Script di test MQTT per simulare richieste energia e ricezione misurazioni
# Richiede mosquitto_pub e mosquitto_sub installati

BROKER=localhost
ENERGY_TOPIC="energy/requests"
POLLUTION_TOPIC="admin/pollution"

# 1. Simula una richiesta energia dal provider
mosquitto_pub -h $BROKER -t $ENERGY_TOPIC -m '{"timestamp":"test123","amountKWh":100}'
echo "[Richiesta energia pubblicata su $ENERGY_TOPIC]"

echo "\n[In ascolto sulle misurazioni di inquinamento per 5 secondi...]"
# 2. Ricevi le misurazioni pubblicate dai plant (ascolta per 5 secondi)
timeout 5 mosquitto_sub -h $BROKER -t $POLLUTION_TOPIC
