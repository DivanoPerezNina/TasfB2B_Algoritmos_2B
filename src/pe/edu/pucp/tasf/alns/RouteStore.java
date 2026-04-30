package pe.edu.pucp.tasf.alns;

public class RouteStore {
    private int[] routeFlightIds;
    private long[] routeDepartureUTC;
    private int[] routeStartByShipment;
    private int[] routeLengthByShipment;
    private int capacity;
    private int size;

    public RouteStore(int initialCapacity) {
        capacity = initialCapacity;
        routeFlightIds = new int[capacity * ConfigExperimentacion.MAX_SALTOS];
        routeDepartureUTC = new long[capacity * ConfigExperimentacion.MAX_SALTOS];
        routeStartByShipment = new int[capacity];
        routeLengthByShipment = new int[capacity];
        size = 0;
        for (int i = 0; i < capacity; i++) {
            routeStartByShipment[i] = -1;
        }
    }

    public int allocateRoute(int length) {
        ensureRouteCapacity(size + length);
        int start = size;
        size += length;
        return start;
    }

    public void setRoute(int shipmentIdx, int start, int length, int[] flightIds, long[] departures) {
        validateShipmentIdx(shipmentIdx);
        routeStartByShipment[shipmentIdx] = start;
        routeLengthByShipment[shipmentIdx] = length;
        for (int i = 0; i < length; i++) {
            routeFlightIds[start + i] = flightIds[i];
            routeDepartureUTC[start + i] = departures[i];
        }
    }

    public int getFlightId(int shipmentIdx, int hop) {
        validateShipmentIdx(shipmentIdx);
        int start = routeStartByShipment[shipmentIdx];
        int length = routeLengthByShipment[shipmentIdx];
        if (start < 0 || hop < 0 || hop >= length) {
            throw new IllegalArgumentException("activeIdx inválido o hop fuera de rango: " + shipmentIdx);
        }
        return routeFlightIds[start + hop];
    }

    public long getDepartureUTC(int shipmentIdx, int hop) {
        validateShipmentIdx(shipmentIdx);
        int start = routeStartByShipment[shipmentIdx];
        int length = routeLengthByShipment[shipmentIdx];
        if (start < 0 || hop < 0 || hop >= length) {
            throw new IllegalArgumentException("activeIdx inválido o hop fuera de rango: " + shipmentIdx);
        }
        return routeDepartureUTC[start + hop];
    }

    public int getRouteLength(int shipmentIdx) {
        validateShipmentIdx(shipmentIdx);
        return routeLengthByShipment[shipmentIdx];
    }

    public void clearRoute(int shipmentIdx) {
        validateShipmentIdx(shipmentIdx);
        routeLengthByShipment[shipmentIdx] = 0;
        routeStartByShipment[shipmentIdx] = -1;
    }

    public void ensureCapacity(int newCapacity) {
        if (newCapacity <= capacity) return;
        int oldCapacity = capacity;
        int newRouteCapacity = newCapacity * ConfigExperimentacion.MAX_SALTOS;

        if (newRouteCapacity > routeFlightIds.length) {
            int[] newFlightIds = new int[newRouteCapacity];
            System.arraycopy(routeFlightIds, 0, newFlightIds, 0, routeFlightIds.length);
            routeFlightIds = newFlightIds;

            long[] newDepartures = new long[newRouteCapacity];
            System.arraycopy(routeDepartureUTC, 0, newDepartures, 0, routeDepartureUTC.length);
            routeDepartureUTC = newDepartures;
        }

        int[] newStart = new int[newCapacity];
        System.arraycopy(routeStartByShipment, 0, newStart, 0, oldCapacity);
        for (int i = oldCapacity; i < newCapacity; i++) {
            newStart[i] = -1;
        }
        routeStartByShipment = newStart;

        int[] newLengths = new int[newCapacity];
        System.arraycopy(routeLengthByShipment, 0, newLengths, 0, oldCapacity);
        routeLengthByShipment = newLengths;

        capacity = newCapacity;
    }

    private void ensureRouteCapacity(int requiredFlightSlots) {
        if (requiredFlightSlots <= routeFlightIds.length) return;
        int newSize = Math.max(routeFlightIds.length * 2, requiredFlightSlots);
        int[] newFlightIds = new int[newSize];
        System.arraycopy(routeFlightIds, 0, newFlightIds, 0, routeFlightIds.length);
        routeFlightIds = newFlightIds;

        long[] newDepartures = new long[newSize];
        System.arraycopy(routeDepartureUTC, 0, newDepartures, 0, routeDepartureUTC.length);
        routeDepartureUTC = newDepartures;
    }

    private void validateShipmentIdx(int shipmentIdx) {
        if (shipmentIdx < 0 || shipmentIdx >= capacity) {
            throw new IllegalArgumentException("activeIdx inválido: " + shipmentIdx);
        }
    }
}
