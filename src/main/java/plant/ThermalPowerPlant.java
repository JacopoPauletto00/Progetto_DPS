package plant;
import plant.grpc.PlantExitMessage;
import java.util.Scanner;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
// Assicurati che il source set includa anche build/generated/source/proto/main/java e grpc
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import plant.grpc.ElectionServiceGrpc;
// Usa sempre plant.grpc.ElectionMessage per i messaggi gRPC
import plant.grpc.ElectionAck;

public class ThermalPowerPlant {
	// Avvia la CLI per comandi interattivi (in un thread separato)
	private void startCliThread() {
		Thread cliThread = new Thread(() -> {
			Scanner scanner = new Scanner(System.in);
			while (true) {
				try {
					System.out.print("Comando (exit per uscire): ");
					String cmd = scanner.nextLine();
					if (cmd == null) {
						System.out.println("Input nullo, riprova.");
						continue;
					}
					if (cmd.trim().equalsIgnoreCase("exit")) {
						leaveNetwork();
						System.out.println("Centrale uscita dalla rete. Arrivederci!");
						System.exit(0);
					} else {
						System.out.println("Comando non riconosciuto.");
					}
				} catch (Exception e) {
					System.err.println("Errore input CLI: " + e.getMessage());
				}
			}
		});
		cliThread.setDaemon(true);
		cliThread.start();
	}
	// Notifica tutti gli altri plant e l'admin server dell'uscita
	private void leaveNetwork() {
		// Notifica gli altri plant via gRPC in parallelo
		if (otherPlants != null) {
			List<Thread> threads = new ArrayList<>();
			for (PlantInfo pi : otherPlants) {
				Thread t = new Thread(() -> {
					try {
						ManagedChannel channel = ManagedChannelBuilder.forAddress(pi.host, pi.port)
							.usePlaintext()
							.build();
						ElectionServiceGrpc.ElectionServiceBlockingStub stub = ElectionServiceGrpc.newBlockingStub(channel);
						PlantExitMessage exitMsg = PlantExitMessage.newBuilder().setSenderId(id).build();
						stub.notifyPlantExit(exitMsg);
						channel.shutdown();
						System.out.println("Notificato exit a " + pi.id);
					} catch (Exception e) {
						System.err.println("Errore notifica exit a " + pi.id + ": " + e.getMessage());
					}
				});
				threads.add(t);
				t.start();
			}
			// Attendi che tutte le notifiche siano inviate
			for (Thread t : threads) {
				try { t.join(); } catch (InterruptedException ignored) {}
			}
		}
		// Notifica l'admin server via REST
		try {
			String urlStr = "http://" + adminServerHost + ":" + adminServerPort + "/leave";
			URL url = new URL(urlStr);
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Content-Type", "application/json");
			conn.setDoOutput(true);
			String json = String.format("{\"id\":\"%s\"}", id);
			try (OutputStream os = conn.getOutputStream()) {
				os.write(json.getBytes());
			}
			int responseCode = conn.getResponseCode();
			if (responseCode == 200) {
				System.out.println("Notifica di uscita inviata all'admin server");
			} else {
				System.err.println("Errore notifica uscita admin server: codice " + responseCode);
			}
		} catch (Exception e) {
			System.err.println("Errore notifica uscita admin server: " + e.getMessage());
		}
	}
	private final String id;
	private final int grpcPort;
	private final String adminServerHost;
	private final int adminServerPort;
	// Lista degli altri plant nella rete (ricevuta dal server admin)
	private List<PlantInfo> otherPlants;
	// Buffer per le misurazioni del sensore
	private MeasurementBuffer buffer;
	private MqttClient mqttClient;
	private static final String ENERGY_TOPIC = "energy/requests";
	private static final String POLLUTION_TOPIC = "admin/pollution";
	// Election state
	private boolean busy = false;
	private String currentRequestId = null;
	private double myPrice = 0.0;
	private PlantElectionMessage bestMsg = null;

	public ThermalPowerPlant(String id, int grpcPort, String adminServerHost, int adminServerPort) {
		this.id = id;
		this.grpcPort = grpcPort;
		this.adminServerHost = adminServerHost;
		this.adminServerPort = adminServerPort;
		this.buffer = new SlidingWindowBuffer();
	}

	private Server grpcServer;

	public void start() {
	// 6. Avvia la CLI interattiva
	startCliThread();
		// 1. Registra la centrale presso il server di amministrazione
		try {
			otherPlants = registerWithAdminServer();
			System.out.println("Registrazione avvenuta. Altri plant trovati: " + otherPlants.size());
		} catch (Exception e) {
			System.err.println("Errore nella registrazione presso il server di amministrazione: " + e.getMessage());
			e.printStackTrace();
			return;
		}

		// 2. Avvia il server gRPC
		try {
			grpcServer = ServerBuilder.forPort(grpcPort)
				.addService(new ElectionServiceImpl())
				.build()
				.start();
			System.out.println("gRPC server avviato sulla porta " + grpcPort);
		} catch (Exception e) {
			System.err.println("Errore avvio gRPC server: " + e.getMessage());
		}

		// 3. Avvia il thread del simulatore sensore
		Thread sensorThread = new Thread(new SensorSimulator(buffer));
		sensorThread.setDaemon(true);
		sensorThread.start();

		// 3. Sottoscrivi il topic MQTT delle richieste energia
		try {
			mqttClient = new MqttClient("tcp://localhost:1883", MqttClient.generateClientId(), new MemoryPersistence());
			mqttClient.connect();
			mqttClient.subscribe(ENERGY_TOPIC, (topic, msg) -> {
				String payload = new String(msg.getPayload());
				onEnergyRequest(payload);
			});
			System.out.println("Sottoscritto a " + ENERGY_TOPIC);
		} catch (MqttException e) {
			System.err.println("Errore MQTT: " + e.getMessage());
		}

		// 4. Presentati agli altri plant se necessario
		if (!otherPlants.isEmpty()) {
			System.out.println("Presentazione agli altri plant");
			for (PlantInfo pi : otherPlants) {
				// Puoi implementare un vero metodo gRPC di presentazione, qui solo log
				System.out.println("[STUB] Presentazione a " + pi.id);
			}
		}

		// 5. Avvia timer per invio medie CO2 ogni 10s
		Timer timer = new Timer();
		timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				sendAveragesToAdmin();
			}
		}, 10_000, 10_000);
	}

	// Effettua la registrazione presso il server admin e riceve la lista degli altri plant
	private List<PlantInfo> registerWithAdminServer() throws Exception {
		String urlStr = "http://" + adminServerHost + ":" + adminServerPort + "/register";
		URL url = new URL(urlStr);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/json");
		conn.setDoOutput(true);

		String json = String.format("{\"id\":\"%s\",\"host\":\"localhost\",\"port\":%d}", id, grpcPort);
		try (OutputStream os = conn.getOutputStream()) {
			os.write(json.getBytes());
		}

		int responseCode = conn.getResponseCode();
		if (responseCode != 200) {
			throw new RuntimeException("Registrazione fallita, codice: " + responseCode);
		}

		List<PlantInfo> plants = new ArrayList<>();
		try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null) sb.append(line);
			// Parsing semplice della risposta JSON (lista di oggetti)
			String resp = sb.toString();
			// Attenzione: parsing minimale, da sostituire con una libreria JSON se necessario
			resp = resp.replace("[", "").replace("]", "");
			if (!resp.trim().isEmpty()) {
				String[] entries = resp.split("},");
				for (String entry : entries) {
					entry = entry.replace("{", "").replace("}", "");
					String[] fields = entry.split(",");
					PlantInfo pi = new PlantInfo();
					for (String f : fields) {
						String[] kv = f.split(":");
						if (kv.length == 2) {
							String key = kv[0].replace("\"", "").trim();
							String value = kv[1].replace("\"", "").trim();
							if (key.equals("id")) pi.id = value;
							else if (key.equals("host")) pi.host = value;
							else if (key.equals("port")) pi.port = Integer.parseInt(value);
						}
					}
					plants.add(pi);
				}
			}
		}
		return plants;
	}

	// Metodo per gestire la ricezione di una richiesta energia via MQTT
	private void onEnergyRequest(String requestJson) {
		// Parsing minimale: estrai requestId (timestamp) e amount
		try {
			String reqId = extractField(requestJson, "timestamp");
			int amount = Integer.parseInt(extractField(requestJson, "amountKWh"));
			if (busy) {
				System.out.println("Plant occupato, ignora richiesta " + reqId);
				return;
			}
			System.out.println("Ricevuta richiesta energia " + reqId + " per " + amount + " kWh");
			// Genera prezzo random [0.1, 0.9]
			myPrice = 0.1 + Math.random() * 0.8;
			currentRequestId = reqId;
			   bestMsg = new PlantElectionMessage(id, myPrice, reqId);
			// Avvia election ring
			startElectionRing(bestMsg);
		} catch (Exception e) {
			System.err.println("Errore parsing richiesta energia: " + e.getMessage());
		}
	}

	// Avvia l'election ring: invia il proprio messaggio al prossimo plant
		private void startElectionRing(PlantElectionMessage msg) {
		PlantInfo next = getNextPlant();
		if (next == null) {
			System.out.println("Nessun altro plant nella rete, gestisco direttamente la richiesta");
			winElection(msg);
			return;
		}
		System.out.println("Avvio election ring verso " + next.id + " con prezzo " + msg.price);
		sendElectionMessage(next, msg);
	}

	// Gestione ricezione messaggio election (gRPC stub)
		public void onElectionMessage(PlantElectionMessage msg) {
		// Solo per la stessa richiesta
		if (!msg.requestId.equals(currentRequestId)) {
			System.out.println("Election msg ignorato (requestId diverso)");
			return;
		}
		// Confronta prezzo/ID
		if (isBetter(msg, bestMsg)) {
			bestMsg = msg;
		}
		// Se il messaggio torna a me, ho vinto
		if (msg.senderId.equals(id)) {
			System.out.println("Election vinta! Plant selezionato: " + bestMsg.senderId + " (prezzo: " + bestMsg.price + ")");
			if (bestMsg.senderId.equals(id)) {
				winElection(bestMsg);
			}
			// Reset stato election
			currentRequestId = null;
			bestMsg = null;
			myPrice = 0.0;
			return;
		}
		// Propaga al prossimo
		PlantInfo next = getNextPlant();
		if (next != null) {
			sendElectionMessage(next, bestMsg);
		}
	}

	// Determina se msg1 è "migliore" di msg2 (prezzo minore, in caso di parità ID maggiore)
		private boolean isBetter(PlantElectionMessage msg1, PlantElectionMessage msg2) {
		if (msg1.price < msg2.price) return true;
		if (msg1.price == msg2.price) return msg1.senderId.compareTo(msg2.senderId) > 0;
		return false;
	}

	// Quando si vince l'election
		private void winElection(PlantElectionMessage winner) {
		if (!winner.senderId.equals(id)) return;
		busy = true;
		System.out.println("Plant " + id + " inizia produzione per richiesta " + winner.requestId);
		// Simula produzione: tempo = 1ms * kWh richiesti
		int kwh = 0;
		try {
			kwh = Integer.parseInt(extractField(winner.requestId, "amountKWh"));
		} catch (Exception e) {
			// fallback: ignora
		}
		int productionTime = kwh > 0 ? kwh : 1000; // fallback 1s
		try {
			Thread.sleep(productionTime);
		} catch (InterruptedException e) {}
		System.out.println("Plant " + id + " ha completato la produzione per richiesta " + winner.requestId);
		busy = false;
	}

	// Trova il prossimo plant nel ring (ordinamento per ID)
	private PlantInfo getNextPlant() {
		if (otherPlants == null || otherPlants.isEmpty()) return null;
		List<String> ids = new ArrayList<>();
		ids.add(id);
		for (PlantInfo pi : otherPlants) ids.add(pi.id);
		ids.sort(String::compareTo);
		int idx = ids.indexOf(id);
		String nextId = ids.get((idx + 1) % ids.size());
		if (nextId.equals(id)) return null;
		for (PlantInfo pi : otherPlants) if (pi.id.equals(nextId)) return pi;
		return null;
	}

	// Invio reale messaggio election via gRPC
		private void sendElectionMessage(PlantInfo dest, PlantElectionMessage msg) {
		ManagedChannel channel = ManagedChannelBuilder.forAddress(dest.host, dest.port)
			.usePlaintext()
			.build();
		   ElectionServiceGrpc.ElectionServiceBlockingStub stub = ElectionServiceGrpc.newBlockingStub(channel);
		   plant.grpc.ElectionMessage grpcMsg = plant.grpc.ElectionMessage.newBuilder()
			   .setSenderId(msg.senderId)
			   .setPrice(msg.price)
			   .setRequestId(msg.requestId)
			   .build();
		try {
			stub.sendElectionMessage(grpcMsg);
		} catch (Exception e) {
			System.err.println("Errore invio gRPC a " + dest.id + ": " + e.getMessage());
		} finally {
			channel.shutdown();
		}
	}

	// Implementazione ElectionService gRPC
		private class ElectionServiceImpl extends ElectionServiceGrpc.ElectionServiceImplBase {
			@Override
			public void sendElectionMessage(plant.grpc.ElectionMessage request,
											io.grpc.stub.StreamObserver<plant.grpc.ElectionAck> responseObserver) {
				onElectionMessage(new PlantElectionMessage(
					request.getSenderId(),
					request.getPrice(),
					request.getRequestId()
				));
				responseObserver.onNext(plant.grpc.ElectionAck.newBuilder().setReceived(true).build());
				responseObserver.onCompleted();
			}

			@Override
			public void notifyPlantExit(plant.grpc.PlantExitMessage request,
										io.grpc.stub.StreamObserver<plant.grpc.ElectionAck> responseObserver) {
				String exitingId = request.getSenderId();
				if (otherPlants != null) {
					otherPlants.removeIf(pi -> pi.id.equals(exitingId));
					System.out.println("Ricevuta notifica di uscita da " + exitingId + ". Aggiornata lista plant.");
				}
				responseObserver.onNext(plant.grpc.ElectionAck.newBuilder().setReceived(true).build());
				responseObserver.onCompleted();
			}
		}

	// Parsing minimale di un campo da JSON (solo per test/demo)
	private String extractField(String json, String field) {
		String[] parts = json.replace("{", "").replace("}", "").split(",");
		for (String p : parts) {
			String[] kv = p.split(":");
			if (kv.length == 2 && kv[0].replace("\"", "").trim().equals(field)) {
				return kv[1].replace("\"", "").trim();
			}
		}
		return "";
	}



	// Metodo per inviare le medie delle misurazioni al server admin via MQTT
	private void sendAveragesToAdmin() {
		List<Double> averages = buffer.readAllAndClean();
		if (averages.isEmpty()) return;
		long now = System.currentTimeMillis();
		StringBuilder sb = new StringBuilder();
		sb.append("{\"plantId\":\"").append(id).append("\",\"timestamp\":").append(now).append(",\"averages\":[");
		for (int i = 0; i < averages.size(); i++) {
			sb.append(averages.get(i));
			if (i < averages.size() - 1) sb.append(",");
		}
		sb.append("]}");
		try {
			MqttMessage msg = new MqttMessage(sb.toString().getBytes());
			msg.setQos(1);
			mqttClient.publish(POLLUTION_TOPIC, msg);
			System.out.println("Inviate medie CO2: " + sb);
		} catch (MqttException e) {
			System.err.println("Errore invio medie CO2: " + e.getMessage());
		}
	}

	// Metodo main per avvio da terminale/VS Code con parametri
    public static void main(String[] args) {
        if (args.length != 4) {
            System.out.println("USO: java plant.ThermalPowerPlant <id> <grpcPort> <adminServerHost> <adminServerPort>");
            System.exit(1);
        }
        String id = args[0];
        int grpcPort = Integer.parseInt(args[1]);
        String adminServerHost = args[2];
        int adminServerPort = Integer.parseInt(args[3]);
        ThermalPowerPlant plant = new ThermalPowerPlant(id, grpcPort, adminServerHost, adminServerPort);
        plant.start();
    }
}

// Classe di supporto per info sugli altri plant
class PlantInfo {
	public String id;
	public String host;
	public int port;
}

// Interfaccia buffer misurazioni
interface MeasurementBuffer {
	void add(Measurement m);
	List<Double> readAllAndClean(); // restituisce le medie calcolate
}

// Implementazione buffer sliding window 8 con overlap 50%
class SlidingWindowBuffer implements MeasurementBuffer {
	private final Queue<Measurement> buffer = new LinkedList<>();
	private final List<Double> averages = new ArrayList<>();
	private static final int WINDOW_SIZE = 8;
	private static final int OVERLAP = 4;

	@Override
	public synchronized void add(Measurement m) {
		buffer.add(m);
		if (buffer.size() == WINDOW_SIZE) {
			double sum = 0;
			for (Measurement mm : buffer) sum += mm.co2;
			averages.add(sum / WINDOW_SIZE);
			// Rimuovi metà buffer (overlap 50%)
			for (int i = 0; i < OVERLAP; i++) buffer.poll();
		}
	}

	@Override
	public synchronized List<Double> readAllAndClean() {
		List<Double> out = new ArrayList<>(averages);
		averages.clear();
		return out;
	}
}

// Simulatore sensore (thread)
class SensorSimulator implements Runnable {
	private final MeasurementBuffer buffer;
	public SensorSimulator(MeasurementBuffer buffer) {
		this.buffer = buffer;
	}
	@Override
	public void run() {
		while (true) {
			try {
				double co2 = 300 + Math.random() * 200; // Simulazione: 300-500g
				long ts = System.currentTimeMillis();
				buffer.add(new Measurement(co2, ts));
				Thread.sleep(1000); // 1 misura al secondo
			} catch (InterruptedException e) {
				break;
			}
		}
	}
}

// Misurazione
class Measurement {
	public double co2;
	public long timestamp;
	public Measurement(double co2, long timestamp) {
		this.co2 = co2;
		this.timestamp = timestamp;
	}
}

// Messaggio election locale (per evitare conflitti con plant.grpc.ElectionMessage)
class PlantElectionMessage {
   public String senderId;
   public double price;
   public String requestId;
   public PlantElectionMessage(String senderId, double price, String requestId) {
	   this.senderId = senderId;
	   this.price = price;
	   this.requestId = requestId;
   }
}
