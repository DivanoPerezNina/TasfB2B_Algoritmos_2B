package pe.edu.pucp.tasf.alns;

public class ShipmentRecord {
    public long id;
    public int origin;
    public int destination;
    public int quantity;
    public long releaseUTC;
    public long deadlineUTC;
    public String clientId;

    public ShipmentRecord(long id, int origin, int destination, int quantity, long releaseUTC, long deadlineUTC, String clientId) {
        this.id = id;
        this.origin = origin;
        this.destination = destination;
        this.quantity = quantity;
        this.releaseUTC = releaseUTC;
        this.deadlineUTC = deadlineUTC;
        this.clientId = clientId;
    }
}