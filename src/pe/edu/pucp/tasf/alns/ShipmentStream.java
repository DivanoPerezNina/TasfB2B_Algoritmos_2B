package pe.edu.pucp.tasf.alns;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class ShipmentStream {
    private BufferedReader reader;
    private int originId;
    private ShipmentRecord next;
    private boolean _hasNext = true;
    
    public long lineasLeidas = 0;
    public long parseadasOK = 0;
    public long lineasInvalidas = 0;

    public ShipmentStream(String filePath, int originId) throws IOException {
        this.reader = new BufferedReader(new FileReader(filePath));
        this.originId = originId;
        this._hasNext = true;
        advance();
    }

    public void advance() throws IOException {
        while (true) {
            String line = reader.readLine();
            lineasLeidas++;
            if (line == null) {
                _hasNext = false;
                next = null;
                return;
            }
            
            if (tryParse(line)) {
                return;
            }
            lineasInvalidas++;
            
            if (ConfigExperimentacion.MAX_ENVIOS_DEBUG > 0 && parseadasOK >= ConfigExperimentacion.MAX_ENVIOS_DEBUG) {
                _hasNext = false;
                next = null;
                return;
            }
        }
    }

    private boolean tryParse(String line) {
        try {
            // Parse line: id-aammdd-hh-mm-dest-###-client
            String[] parts = line.split("-");
            if (parts.length < 7) return false;
            
            long id = Long.parseLong(parts[0]);
            String date = parts[1];
            String hour = parts[2];
            String min = parts[3];
            String dest = parts[4];
            int qty = Integer.parseInt(parts[5]);
            String client = parts[6];

            // date debe tener 8 dígitos: AAAAMMDD
            if (date.length() != 8) return false;
            int year = Integer.parseInt(date.substring(0, 4));
            int month = Integer.parseInt(date.substring(4, 6));
            int day = Integer.parseInt(date.substring(6, 8));
            
            if (month < 1 || month > 12 || day < 1 || day > 31) return false;
            
            int h = Integer.parseInt(hour);
            int m = Integer.parseInt(min);
            
            if (h < 0 || h > 23 || m < 0 || m > 59) return false;
            
            if (!DatosEstaticos.airportCodeToId.containsKey(dest)) return false;

            // Convertir fecha y hora a minutos UTC
            int gmtOrigin = DatosEstaticos.airportGmt[originId];
            long localMinutes = DatosEstaticos.calcularEpochMinutos(year, month, day, h, m, 0);
            long releaseUTC = localMinutes - gmtOrigin * 60L;

            int destId = DatosEstaticos.airportCodeToId.get(dest);
            long deadlineUTC = releaseUTC + (DatosEstaticos.airportContinent[originId].equals(DatosEstaticos.airportContinent[destId]) ?
                ConfigExperimentacion.SLA_MISMO_CONTINENTE_MINUTOS : ConfigExperimentacion.SLA_DISTINTO_CONTINENTE_MINUTOS);

            next = new ShipmentRecord(id, originId, destId, qty, releaseUTC, deadlineUTC, client);
            parseadasOK++;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean hasNext() {
        return _hasNext;
    }

    public ShipmentRecord peek() {
        return next;
    }

    public void close() throws IOException {
        reader.close();
    }
}