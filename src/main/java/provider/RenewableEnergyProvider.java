package provider;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

public class RenewableEnergyProvider {
	private static final String MQTT_BROKER = "tcp://localhost:1883";
	private static final String TOPIC = "energy/requests";
	private static final int PERIOD_MS = 10_000;
	private final Random random = new Random();
	private MqttClient client;

	public RenewableEnergyProvider() throws MqttException {
		client = new MqttClient(MQTT_BROKER, MqttClient.generateClientId(), new MemoryPersistence());
		client.connect();
	}

	public void start() {
		Timer timer = new Timer();
		timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				int amount = 5000 + random.nextInt(10001); // 5000-15000
				long timestamp = System.currentTimeMillis();
				EnergyRequest req = new EnergyRequest(amount, timestamp);
				publishRequest(req);
			}
		}, 0, PERIOD_MS);
	}

	private void publishRequest(EnergyRequest req) {
		try {
			MqttMessage message = new MqttMessage(req.toString().getBytes());
			message.setQos(1);
			client.publish(TOPIC, message);
			System.out.println("Published: " + req);
		} catch (MqttException e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) throws Exception {
		RenewableEnergyProvider provider = new RenewableEnergyProvider();
		provider.start();
	}
}
