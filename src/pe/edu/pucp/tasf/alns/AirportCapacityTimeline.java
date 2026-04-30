package pe.edu.pucp.tasf.alns;

import java.util.ArrayList;
import java.util.List;

public class AirportCapacityTimeline {
    private List<Reservation>[] reservations;

    @SuppressWarnings("unchecked")
    public AirportCapacityTimeline(int numAirports) {
        reservations = new ArrayList[numAirports];
        for (int i = 0; i < numAirports; i++) {
            reservations[i] = new ArrayList<>();
        }
    }

    public boolean canReserveInterval(int airportId, long startUTC, long endUTC, int quantity) {
        int cap = DatosEstaticos.airportCapacity[airportId];
        int used = 0;
        for (Reservation r : reservations[airportId]) {
            if (r.overlaps(startUTC, endUTC)) {
                used += r.quantity;
            }
        }
        return cap - used >= quantity;
    }

    public void reserveInterval(int airportId, long startUTC, long endUTC, int quantity) {
        reservations[airportId].add(new Reservation(startUTC, endUTC, quantity));
    }

    public void releaseInterval(int airportId, long startUTC, long endUTC, int quantity) {
        // Encontrar y remover
        for (Reservation r : reservations[airportId]) {
            if (r.start == startUTC && r.end == endUTC && r.quantity == quantity) {
                reservations[airportId].remove(r);
                break;
            }
        }
    }

    private static class Reservation {
        long start, end;
        int quantity;

        Reservation(long s, long e, int q) {
            start = s;
            end = e;
            quantity = q;
        }

        boolean overlaps(long s, long e) {
            return start < e && s < end;
        }
    }
}