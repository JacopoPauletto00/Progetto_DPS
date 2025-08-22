
package adminclient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

public class AdminClient {
	private static final String SERVER_URL = "http://localhost:8080";

	public static void main(String[] args) throws Exception {
		Scanner scanner = new Scanner(System.in);
		while (true) {
			System.out.println("\n--- Administration Client ---");
			System.out.println("1. Elenca centrali termiche registrate");
			System.out.println("2. Calcola media emissioni CO2 tra due timestamp");
			System.out.println("0. Esci");
			System.out.print("Scelta: ");
			String choice = scanner.nextLine();
			switch (choice) {
				case "1":
					listPlants();
					break;
				case "2":
					System.out.print("Timestamp inizio (ms): ");
					long from = Long.parseLong(scanner.nextLine());
					System.out.print("Timestamp fine (ms): ");
					long to = Long.parseLong(scanner.nextLine());
					getAverageCO2(from, to);
					break;
				case "0":
					System.exit(0);
				default:
					System.out.println("Scelta non valida.");
			}
		}
	}

	private static void listPlants() throws Exception {
		URL url = new URL(SERVER_URL + "/plants");
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("GET");
		int responseCode = conn.getResponseCode();
		if (responseCode == 200) {
			try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
				String line;
				System.out.println("\nCentrali registrate:");
				while ((line = br.readLine()) != null) {
					System.out.println(line);
				}
			}
		} else {
			System.out.println("Errore: " + responseCode);
		}
	}

	private static void getAverageCO2(long from, long to) throws Exception {
		String urlStr = SERVER_URL + "/stats?from=" + from + "&to=" + to;
		URL url = new URL(urlStr);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("GET");
		int responseCode = conn.getResponseCode();
		if (responseCode == 200) {
			try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
				String line;
				System.out.println("\nRisultato:");
				while ((line = br.readLine()) != null) {
					System.out.println(line);
				}
			}
		} else {
			System.out.println("Errore: " + responseCode);
		}
	}
}
