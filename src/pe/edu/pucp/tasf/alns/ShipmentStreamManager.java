package pe.edu.pucp.tasf.alns;

import java.io.Closeable;
import java.io.IOException;
import java.util.PriorityQueue;

public class ShipmentStreamManager implements Closeable {
    private PriorityQueue<ShipmentStream> queue;
    private int streamsAbiertos = 0;

    public ShipmentStreamManager() throws IOException {
        queue = new PriorityQueue<>((a, b) -> {
            ShipmentRecord recA = a.peek();
            ShipmentRecord recB = b.peek();
            if (recA == null && recB == null) return 0;
            if (recA == null) return 1;
            if (recB == null) return -1;
            return Long.compare(recA.releaseUTC, recB.releaseUTC);
        });
        
        // Abrir streams para cada archivo _envios_XXXX_.txt
        for (String code : DatosEstaticos.airportCode) {
            String filePath = ConfigExperimentacion.ENVIOS_DIR + "_envios_" + code + "_.txt";
            try {
                int originId = DatosEstaticos.airportCodeToId.get(code);
                ShipmentStream stream = new ShipmentStream(filePath, originId);
                if (stream.hasNext()) {
                    queue.add(stream);
                    streamsAbiertos++;
                } else {
                    stream.close();
                }
            } catch (IOException e) {
                // Archivo no existe, ignorar
            }
        }
    }

    public int getStreamsAbiertos() {
        return streamsAbiertos;
    }

    public boolean hasNext() {
        return !queue.isEmpty() && queue.peek() != null && queue.peek().peek() != null;
    }

    public boolean hasNextBefore(long utc) {
        if (queue.isEmpty()) return false;
        ShipmentStream s = queue.peek();
        return s != null && s.peek() != null && s.peek().releaseUTC < utc;
    }

    public long peekNextReleaseUTC() {
        if (!hasNext()) {
            return Long.MAX_VALUE;
        }
        ShipmentRecord rec = queue.peek().peek();
        return rec != null ? rec.releaseUTC : Long.MAX_VALUE;
    }

    public ShipmentRecord pollNext() throws IOException {
        ShipmentStream stream = queue.poll();
        if (stream == null || stream.peek() == null) return null;
        
        ShipmentRecord rec = stream.peek();
        stream.advance();
        
        if (stream.hasNext()) {
            queue.add(stream);
        } else {
            stream.close();
            streamsAbiertos--;
        }
        return rec;
    }

    @Override
    public void close() throws IOException {
        while (!queue.isEmpty()) {
            ShipmentStream s = queue.poll();
            if (s != null) {
                s.close();
            }
        }
        streamsAbiertos = 0;
    }
}