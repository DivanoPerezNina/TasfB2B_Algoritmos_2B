package pe.edu.pucp.tasf;

import java.time.ZonedDateTime;
import java.util.*;

/**
 * Interfaz para operadores repair.
 */
public interface OperadorRepair {
    void repair(SolucionALNS solucion, List<RutaEnvio> removidos, GestorDatos datos, ConfigExperimentacion config, Random rand);
}

// Implementaciones
class GreedyInsertion implements OperadorRepair {
    @Override
    public void repair(SolucionALNS solucion, List<RutaEnvio> removidos, GestorDatos datos, ConfigExperimentacion config, Random rand) {
        for (RutaEnvio r : removidos) {
            // Encontrar mejor ruta factible
            RutaEnvio mejor = encontrarMejorRuta(r.envio, datos, config);
            if (mejor != null) {
                r.tramos.addAll(mejor.tramos);
                r.calcularMetricas();
            }
        }
    }

    public static RutaEnvio encontrarMejorRuta(GestorDatos.Envio envio, GestorDatos datos, ConfigExperimentacion config) {
        List<RutaEnvio> candidatos = new ArrayList<>();
        
        // 1. Rutas directas (1 hop)
        List<GestorDatos.Vuelo> vuelosDirectos = datos.vuelosPorOrigen.get(envio.origen);
        if (vuelosDirectos != null) {
            for (GestorDatos.Vuelo v : vuelosDirectos) {
                if (v.destino.equals(envio.destino)) {
                    RutaEnvio ruta = crearRutaDirecta(envio, v, datos);
                    if (ruta != null) candidatos.add(ruta);
                }
            }
        }
        
        // 2. Rutas con 1 escala (2 hops)
        if (candidatos.isEmpty() && vuelosDirectos != null) {
            for (GestorDatos.Vuelo v1 : vuelosDirectos) {
                List<GestorDatos.Vuelo> conexiones = datos.vuelosPorOrigen.get(v1.destino);
                if (conexiones != null) {
                    for (GestorDatos.Vuelo v2 : conexiones) {
                        if (v2.destino.equals(envio.destino)) {
                            RutaEnvio ruta = crearRutaUnaEscala(envio, v1, v2, datos);
                            if (ruta != null) candidatos.add(ruta);
                        }
                    }
                }
            }
        }
        
        // 3. Rutas con 2 escalas (3 hops) - solo si no hay mejores opciones
        if (candidatos.isEmpty() && vuelosDirectos != null) {
            for (GestorDatos.Vuelo v1 : vuelosDirectos) {
                List<GestorDatos.Vuelo> conexiones1 = datos.vuelosPorOrigen.get(v1.destino);
                if (conexiones1 != null) {
                    for (GestorDatos.Vuelo v2 : conexiones1) {
                        List<GestorDatos.Vuelo> conexiones2 = datos.vuelosPorOrigen.get(v2.destino);
                        if (conexiones2 != null) {
                            for (GestorDatos.Vuelo v3 : conexiones2) {
                                if (v3.destino.equals(envio.destino)) {
                                    RutaEnvio ruta = crearRutaDosEscalas(envio, v1, v2, v3, datos);
                                    if (ruta != null) candidatos.add(ruta);
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Seleccionar mejor candidato (menor tiempo total o mejor puntuación)
        RutaEnvio mejor = null;
        ZonedDateTime mejorLlegada = ZonedDateTime.now().plusYears(100); // fecha futura
        for (RutaEnvio r : candidatos) {
            if (!r.tramos.isEmpty()) {
                ZonedDateTime llegadaFinal = r.tramos.get(r.tramos.size() - 1).llegadaReal;
                if (llegadaFinal.isBefore(mejorLlegada)) {
                    mejorLlegada = llegadaFinal;
                    mejor = r;
                }
            }
        }
        return mejor;
    }
    
    private static RutaEnvio crearRutaDirecta(GestorDatos.Envio envio, GestorDatos.Vuelo vuelo, GestorDatos datos) {
        GestorDatos.Aeropuerto aeroOrig = datos.aeropuertos.get(vuelo.origen);
        GestorDatos.Aeropuerto aeroDest = datos.aeropuertos.get(vuelo.destino);
        if (aeroOrig == null || aeroDest == null) return null;
        
        RutaEnvio ruta = new RutaEnvio(envio);
        ZonedDateTime salida = UtilTiempo.calcularSalidaReal(vuelo, envio.registroUTC, aeroOrig);
        ZonedDateTime llegada = UtilTiempo.calcularLlegadaReal(salida, vuelo, aeroDest);
        
        // Verificar factibilidad básica
        if (!UtilTiempo.esFactible(ruta, envio.deadlineUTC)) return null;
        
        ruta.tramos.add(new TramoAsignado(vuelo, salida, llegada, 0));
        ruta.calcularMetricas();
        return ruta;
    }
    
    private static RutaEnvio crearRutaUnaEscala(GestorDatos.Envio envio, GestorDatos.Vuelo v1, GestorDatos.Vuelo v2, GestorDatos datos) {
        GestorDatos.Aeropuerto aeroOrig = datos.aeropuertos.get(v1.origen);
        GestorDatos.Aeropuerto aeroInter = datos.aeropuertos.get(v1.destino);
        GestorDatos.Aeropuerto aeroDest = datos.aeropuertos.get(v2.destino);
        if (aeroOrig == null || aeroInter == null || aeroDest == null) return null;
        
        RutaEnvio ruta = new RutaEnvio(envio);
        
        // Primer tramo
        ZonedDateTime salida1 = UtilTiempo.calcularSalidaReal(v1, envio.registroUTC, aeroOrig);
        ZonedDateTime llegada1 = UtilTiempo.calcularLlegadaReal(salida1, v1, aeroInter);
        ruta.tramos.add(new TramoAsignado(v1, salida1, llegada1, 0));
        
        // Segundo tramo (con tiempo de conexión mínimo)
        ZonedDateTime salida2 = UtilTiempo.calcularSalidaReal(v2, llegada1.plusMinutes(60), aeroInter); // mínimo 1 hora conexión
        if (!UtilTiempo.esFactible(llegada1, salida2, 60)) return null; // verificar conexión factible
        
        ZonedDateTime llegada2 = UtilTiempo.calcularLlegadaReal(salida2, v2, aeroDest);
        ruta.tramos.add(new TramoAsignado(v2, salida2, llegada2, 0));
        
        // Verificar factibilidad con deadline
        if (!UtilTiempo.esFactible(ruta, envio.deadlineUTC)) return null;
        
        ruta.calcularMetricas();
        return ruta;
    }
    
    private static RutaEnvio crearRutaDosEscalas(GestorDatos.Envio envio, GestorDatos.Vuelo v1, GestorDatos.Vuelo v2, GestorDatos.Vuelo v3, GestorDatos datos) {
        GestorDatos.Aeropuerto aeroOrig = datos.aeropuertos.get(v1.origen);
        GestorDatos.Aeropuerto aeroInter1 = datos.aeropuertos.get(v1.destino);
        GestorDatos.Aeropuerto aeroInter2 = datos.aeropuertos.get(v2.destino);
        GestorDatos.Aeropuerto aeroDest = datos.aeropuertos.get(v3.destino);
        if (aeroOrig == null || aeroInter1 == null || aeroInter2 == null || aeroDest == null) return null;
        
        RutaEnvio ruta = new RutaEnvio(envio);
        
        // Primer tramo
        ZonedDateTime salida1 = UtilTiempo.calcularSalidaReal(v1, envio.registroUTC, aeroOrig);
        ZonedDateTime llegada1 = UtilTiempo.calcularLlegadaReal(salida1, v1, aeroInter1);
        ruta.tramos.add(new TramoAsignado(v1, salida1, llegada1, 0));
        
        // Segundo tramo
        ZonedDateTime salida2 = UtilTiempo.calcularSalidaReal(v2, llegada1.plusMinutes(60), aeroInter1);
        if (!UtilTiempo.esFactible(llegada1, salida2, 60)) return null;
        ZonedDateTime llegada2 = UtilTiempo.calcularLlegadaReal(salida2, v2, aeroInter2);
        ruta.tramos.add(new TramoAsignado(v2, salida2, llegada2, 0));
        
        // Tercer tramo
        ZonedDateTime salida3 = UtilTiempo.calcularSalidaReal(v3, llegada2.plusMinutes(60), aeroInter2);
        if (!UtilTiempo.esFactible(llegada2, salida3, 60)) return null;
        ZonedDateTime llegada3 = UtilTiempo.calcularLlegadaReal(salida3, v3, aeroDest);
        ruta.tramos.add(new TramoAsignado(v3, salida3, llegada3, 0));
        
        // Verificar factibilidad
        if (!UtilTiempo.esFactible(ruta, envio.deadlineUTC)) return null;
        
        ruta.calcularMetricas();
        return ruta;
    }
}

// Agregar Regret2Insertion, UrgencyInsertion, CapacityAwareInsertion