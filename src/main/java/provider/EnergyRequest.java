package provider;

public class EnergyRequest {
    private final int amountKWh;
    private final long timestamp;

    public EnergyRequest(int amountKWh, long timestamp) {
        this.amountKWh = amountKWh;
        this.timestamp = timestamp;
    }

    public int getAmountKWh() {
        return amountKWh;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "{\"amountKWh\":" + amountKWh + ",\"timestamp\":" + timestamp + "}";
    }
}
