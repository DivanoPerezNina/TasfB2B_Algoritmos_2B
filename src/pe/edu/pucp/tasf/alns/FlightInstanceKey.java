package pe.edu.pucp.tasf.alns;
/**
 * Clave para identificar instancia de vuelo.
 */
public class FlightInstanceKey {
    public String vueloId;
    public int diaAbsoluto;

    public FlightInstanceKey(String vueloId, int diaAbsoluto) {
        this.vueloId = vueloId;
        this.diaAbsoluto = diaAbsoluto;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FlightInstanceKey that = (FlightInstanceKey) o;
        return diaAbsoluto == that.diaAbsoluto && vueloId.equals(that.vueloId);
    }

    @Override
    public int hashCode() {
        return vueloId.hashCode() * 31 + diaAbsoluto;
    }

    @Override
    public String toString() {
        return vueloId + "_" + diaAbsoluto;
    }
}