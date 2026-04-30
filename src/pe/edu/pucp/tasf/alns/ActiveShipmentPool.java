package pe.edu.pucp.tasf.alns;

public class ActiveShipmentPool {
    public static final byte NUEVO = 0;
    public static final byte EN_ALMACEN = 1;
    public static final byte PLANIFICADO = 2;
    public static final byte EN_VUELO = 3;
    public static final byte ENTREGADO = 4;
    public static final byte PENDIENTE = 5;
    public static final byte SIN_RUTA = 6;
    public static final byte RETRASADO = 7;
    public static final byte NO_FACTIBLE_ESTRUCTURAL = 8;

    private long[] shipmentId;
    private int[] origin;
    private int[] destination;
    private int[] quantity;
    private long[] releaseUTC;
    private long[] deadlineUTC;
    private int[] currentAirport;
    private byte[] status;
    private int[] routeStart;
    private int[] routeLength;

    private int[] freeStack;
    private int freeTop;
    private int size;
    private int capacity;

    public ActiveShipmentPool(int initialCapacity) {
        capacity = initialCapacity;
        shipmentId = new long[capacity];
        origin = new int[capacity];
        destination = new int[capacity];
        quantity = new int[capacity];
        releaseUTC = new long[capacity];
        deadlineUTC = new long[capacity];
        currentAirport = new int[capacity];
        status = new byte[capacity];
        routeStart = new int[capacity];
        routeLength = new int[capacity];
        freeStack = new int[capacity];
        freeTop = 0;
        size = 0;
    }

    public int allocate() {
        if (freeTop > 0) {
            return freeStack[--freeTop];
        }
        if (size >= capacity) {
            expand();
        }
        return size++;
    }

    private void expand() {
        int newCap = Math.max(1, capacity * 2);

        long[] newShipmentId = new long[newCap];
        System.arraycopy(shipmentId, 0, newShipmentId, 0, capacity);
        shipmentId = newShipmentId;

        int[] newOrigin = new int[newCap];
        System.arraycopy(origin, 0, newOrigin, 0, capacity);
        origin = newOrigin;

        int[] newDestination = new int[newCap];
        System.arraycopy(destination, 0, newDestination, 0, capacity);
        destination = newDestination;

        int[] newQuantity = new int[newCap];
        System.arraycopy(quantity, 0, newQuantity, 0, capacity);
        quantity = newQuantity;

        long[] newReleaseUTC = new long[newCap];
        System.arraycopy(releaseUTC, 0, newReleaseUTC, 0, capacity);
        releaseUTC = newReleaseUTC;

        long[] newDeadlineUTC = new long[newCap];
        System.arraycopy(deadlineUTC, 0, newDeadlineUTC, 0, capacity);
        deadlineUTC = newDeadlineUTC;

        int[] newCurrentAirport = new int[newCap];
        System.arraycopy(currentAirport, 0, newCurrentAirport, 0, capacity);
        currentAirport = newCurrentAirport;

        byte[] newStatus = new byte[newCap];
        System.arraycopy(status, 0, newStatus, 0, capacity);
        status = newStatus;

        int[] newRouteStart = new int[newCap];
        System.arraycopy(routeStart, 0, newRouteStart, 0, capacity);
        routeStart = newRouteStart;

        int[] newRouteLength = new int[newCap];
        System.arraycopy(routeLength, 0, newRouteLength, 0, capacity);
        routeLength = newRouteLength;

        int[] newFreeStack = new int[newCap];
        System.arraycopy(freeStack, 0, newFreeStack, 0, freeTop);
        freeStack = newFreeStack;

        capacity = newCap;
    }

    public void release(int idx) {
        if (idx < 0 || idx >= capacity) {
            return;
        }
        if (freeTop >= freeStack.length) {
            expand();
        }
        freeStack[freeTop++] = idx;
    }

    public int addShipment(ShipmentRecord rec) {
        int idx = allocate();
        shipmentId[idx] = rec.id;
        origin[idx] = rec.origin;
        destination[idx] = rec.destination;
        quantity[idx] = rec.quantity;
        releaseUTC[idx] = rec.releaseUTC;
        deadlineUTC[idx] = rec.deadlineUTC;
        currentAirport[idx] = rec.origin;
        status[idx] = NUEVO;
        routeStart[idx] = -1;
        routeLength[idx] = 0;
        return idx;
    }

    // Getters
    public long getShipmentId(int idx) { return shipmentId[idx]; }
    public int getOrigin(int idx) { return origin[idx]; }
    public int getDestination(int idx) { return destination[idx]; }
    public int getQuantity(int idx) { return quantity[idx]; }
    public long getReleaseUTC(int idx) { return releaseUTC[idx]; }
    public long getDeadlineUTC(int idx) { return deadlineUTC[idx]; }
    public int getCurrentAirport(int idx) { return currentAirport[idx]; }
    public byte getStatus(int idx) { return status[idx]; }
    public void setStatus(int idx, byte s) { status[idx] = s; }
    public int getRouteStart(int idx) { return routeStart[idx]; }
    public int getRouteLength(int idx) { return routeLength[idx]; }
    public int getSize() { return size; }
    public int getCapacity() { return capacity; }
}