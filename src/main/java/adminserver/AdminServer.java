package adminserver;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;


//@ApplicationPath("/")
@Path("/api")
public class AdminServer {
	@Path("/leave")
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	public synchronized Response leaveNetwork(PlantInfo plant) {
		if (plants.remove(plant.id) != null) {
			System.out.println("Plant " + plant.id + " rimosso dalla rete su richiesta di uscita.");
			return Response.ok().build();
		} else {
			return Response.status(Status.NOT_FOUND).entity("Plant non trovato").build();
		}
	}
	// Thread-safe map: plantId -> PlantInfo
	private static final Map<String, PlantInfo> plants = new ConcurrentHashMap<>();
	// Thread-safe list: ogni entry = (plantId, timestamp, avg)
	private static final List<PollutionStat> pollutionStats = new CopyOnWriteArrayList<>();
	private static final String MQTT_BROKER = "tcp://localhost:1883";
	private static final String POLLUTION_TOPIC = "admin/pollution";

	public AdminServer() {
		// Avvia sottoscrizione MQTT
		new Thread(this::subscribeMqtt).start();
	}

	private void subscribeMqtt() {
		try {
			MqttClient client = new MqttClient(MQTT_BROKER, MqttClient.generateClientId(), new MemoryPersistence());
			client.connect();
			client.subscribe(POLLUTION_TOPIC, (topic, msg) -> {
				String payload = new String(msg.getPayload());
				handlePollutionMsg(payload);
			});
			System.out.println("AdminServer sottoscritto a " + POLLUTION_TOPIC);
		} catch (MqttException e) {
			System.err.println("Errore MQTT AdminServer: " + e.getMessage());
		}
	}

	private void handlePollutionMsg(String json) {
		// Parsing minimale: {"plantId":"...","timestamp":...,"averages":[...]} 
		try {
			String plantId = extractField(json, "plantId");
			String tsStr = extractField(json, "timestamp");
			String avgStr = json.substring(json.indexOf("["), json.indexOf("]")+1);
			long ts = Long.parseLong(tsStr);
			avgStr = avgStr.replace("[","").replace("]","");
			for (String avg : avgStr.split(",")) {
				if (!avg.trim().isEmpty()) {
					pollutionStats.add(new PollutionStat(plantId, ts, Double.parseDouble(avg.trim())));
				}
			}
		} catch (Exception e) {
			System.err.println("Errore parsing pollutionMsg: " + e.getMessage());
		}
	}

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

	@Path("/register")
	@PUT
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public synchronized Response registerPlant(PlantInfo plant) {
		if (plants.containsKey(plant.id)) {
			return Response.status(Status.CONFLICT).entity("Plant ID already exists").build();
		}
		plants.put(plant.id, plant);
		// Restituisci la lista degli altri plant (escludendo quello appena aggiunto)
		List<PlantInfo> others = new ArrayList<>(plants.values());
		others.remove(plant);
		return Response.ok(others).build();
	}

	@Path("/plants")
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public Collection<PlantInfo> getPlants() {
		return plants.values();
	}

	@Path("/stats")
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public Response getStats(@QueryParam("from") long from, @QueryParam("to") long to) {
		// Calcola la media delle emissioni tra t1 e t2
		List<Double> values = new ArrayList<>();
		for (PollutionStat stat : pollutionStats) {
			if (stat.timestamp >= from && stat.timestamp <= to) {
				values.add(stat.avg);
			}
		}
		double avg = values.isEmpty() ? 0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
		return Response.ok(Collections.singletonMap("averageCO2", avg)).build();
	}

	// Launcher main per avvio standalone con Jersey/Grizzly
	public static void main(String[] args) {
		String baseUri = "http://localhost:8080/";
		//org.glassfish.jersey.server.ResourceConfig config = new org.glassfish.jersey.server.ResourceConfig(AdminServer.class);
		try {
			org.glassfish.grizzly.http.server.HttpServer server =
				org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory.createHttpServer(
					java.net.URI.create(baseUri));
			System.out.println("AdminServer REST avviato su " + baseUri);
			System.out.println("Premi invio per terminare...");
			System.in.read();
			server.shutdownNow();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}

// Info centrale
class PlantInfo {
	public String id;
	public String host;
	public int port;
}

// Statistica emissione
class PollutionStat {
	public String plantId;
	public long timestamp;
	public double avg;
	public PollutionStat(String plantId, long timestamp, double avg) {
		this.plantId = plantId;
		this.timestamp = timestamp;
		this.avg = avg;
	}
}


