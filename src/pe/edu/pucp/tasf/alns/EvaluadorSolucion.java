package pe.edu.pucp.tasf.alns;

public class EvaluadorSolucion {
    public static double calcularFitness(ActiveShipmentPool pool, RouteStore routes, long currentTime) {
        int sinRuta = 0, noFactible = 0, retrasados = 0, pendientes = 0;
        double minutosRetraso = 0;
        int saturacionAeropuerto = 0; // Placeholder
        double transitoTotal = 0;
        int cambiosRuta = 0; // Placeholder

        for (int i = 0; i < pool.getSize(); i++) {
            byte status = pool.getStatus(i);
            if (status == ActiveShipmentPool.SIN_RUTA) sinRuta++;
            else if (status == ActiveShipmentPool.NO_FACTIBLE_ESTRUCTURAL) noFactible++;
            else if (status == ActiveShipmentPool.RETRASADO) retrasados++;
            else if (status == ActiveShipmentPool.PENDIENTE) pendientes++;
            // Calcular retrasos
            if (routes.getRouteLength(i) > 0) {
                long arrival = routes.getDepartureUTC(i, routes.getRouteLength(i) - 1) + 60; // Aprox
                if (arrival > pool.getDeadlineUTC(i)) {
                    minutosRetraso += arrival - pool.getDeadlineUTC(i);
                }
            }
        }

        return sinRuta * ConfigExperimentacion.PESO_MUY_ALTO +
               noFactible * ConfigExperimentacion.PESO_MUY_ALTO +
               retrasados * ConfigExperimentacion.PESO_ALTO +
               minutosRetraso * ConfigExperimentacion.PESO_MEDIO +
               pendientes * ConfigExperimentacion.PESO_MEDIO +
               saturacionAeropuerto * ConfigExperimentacion.PESO_MEDIO +
               transitoTotal * ConfigExperimentacion.PESO_BAJO +
               cambiosRuta * ConfigExperimentacion.PESO_BAJO;
    }
}