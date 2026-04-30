package pe.edu.pucp.tasf.alns;

import java.util.HashMap;
import java.util.Map;

public class FlightCapacityStore {
    private Map<Long, Integer> capacityMap = new HashMap<>();

    public boolean canReserve(int flightId, long departureUTC, int quantity) {
        long dayAbs = departureUTC / 1440;
        long key = (long) flightId * 100000L + dayAbs;
        int current = capacityMap.getOrDefault(key, DatosEstaticos.flightCapacity[flightId]);
        return current >= quantity;
    }

    public void reserve(int flightId, long departureUTC, int quantity) {
        long dayAbs = departureUTC / 1440;
        long key = (long) flightId * 100000L + dayAbs;
        int current = capacityMap.getOrDefault(key, DatosEstaticos.flightCapacity[flightId]);
        capacityMap.put(key, current - quantity);
    }

    public void release(int flightId, long departureUTC, int quantity) {
        long dayAbs = departureUTC / 1440;
        long key = (long) flightId * 100000L + dayAbs;
        int current = capacityMap.getOrDefault(key, DatosEstaticos.flightCapacity[flightId]);
        capacityMap.put(key, current + quantity);
    }
}