package pe.edu.pucp.tasf.alns;

public class AuditorALNS {
    public static boolean validarRuta(int shipmentIdx, ActiveShipmentPool pool, RouteStore routes, FlightCapacityStore flights, AirportCapacityTimeline airports) {
        int length = routes.getRouteLength(shipmentIdx);
        if (length == 0) return false;
        int currentAirport = pool.getOrigin(shipmentIdx);
        long currentTime = pool.getReleaseUTC(shipmentIdx);
        for (int hop = 0; hop < length; hop++) {
            int flightId = routes.getFlightId(shipmentIdx, hop);
            long depUTC = routes.getDepartureUTC(shipmentIdx, hop);
            if (depUTC < currentTime) return false;
            if (!flights.canReserve(flightId, depUTC, pool.getQuantity(shipmentIdx))) return false;
            int dest = DatosEstaticos.flightDestination[flightId];
            long arrUTC = depUTC + (DatosEstaticos.flightArrivalUTCMinuteOfDay[flightId] - DatosEstaticos.flightDepartureUTCMinuteOfDay[flightId]);
            if (hop < length - 1) {
                // Escala
                if (arrUTC + ConfigExperimentacion.TIEMPO_MINIMO_ESCALA_MINUTOS > routes.getDepartureUTC(shipmentIdx, hop + 1)) return false;
                if (!airports.canReserveInterval(dest, arrUTC, routes.getDepartureUTC(shipmentIdx, hop + 1), pool.getQuantity(shipmentIdx))) return false;
            } else {
                // Destino final, no ocupa
            }
            currentAirport = dest;
            currentTime = arrUTC;
        }
        return currentAirport == pool.getDestination(shipmentIdx) && currentTime <= pool.getDeadlineUTC(shipmentIdx);
    }
}