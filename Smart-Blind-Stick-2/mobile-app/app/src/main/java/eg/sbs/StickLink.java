package eg.sbs;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Handler;
import android.os.ParcelUuid;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@SuppressLint("MissingPermission")
final class StickLink {
    private static final UUID SERVICE = UUID.fromString("6f7d0001-48c0-4d8c-bf23-dcb46985b611");
    private static final UUID DATA = UUID.fromString("6f7d0002-48c0-4d8c-bf23-dcb46985b611");
    private final Context context;
    private final Handler handler;
    private final Consumer<String> status;
    private final Consumer<Packet> packets;
    private BluetoothDevice selected;
    private BluetoothGatt gatt;
    private BluetoothLeScanner scanner;
    private boolean active;
    private boolean pairingAllowed;
    private final Runnable giveUp = () -> {
        stopScan();
        status("Stick search timed out. Check power and Bluetooth.");
    };
    private final Runnable watchdog = () -> lost("Stick data interrupted");

    StickLink(Context context, Handler handler, Consumer<String> status, Consumer<Packet> packets) {
        this.context = context;
        this.handler = handler;
        this.status = status;
        this.packets = packets;
    }
    void start() {
        stop();
        active = true;
        pairingAllowed = true;
        if (selected != null) {
            connect();
            return;
        }
        BluetoothAdapter adapter = context.getSystemService(BluetoothManager.class).getAdapter();
        scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (scanner == null) {
            status("Bluetooth unavailable. Enable it and try again.");
            return;
        }
        scanner.startScan(List.of(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build()),
            new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan);
        status("Searching. Turn on only your stick for first pairing.");
        handler.postDelayed(giveUp, 12000);
    }
    private final ScanCallback scan = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            handler.post(() -> {
                if (!active || scanner == null) return;
                selected = result.getDevice();
                stopScan();
                connect();
            });
        }
        @Override public void onScanFailed(int code) {
            handler.post(() -> {
                stopScan();
                status("Bluetooth scan error " + code);
            });
        }
    };
    private void stopScan() {
        handler.removeCallbacks(giveUp);
        if (scanner != null) {
            try { scanner.stopScan(scan); } catch (SecurityException ignored) {}
            scanner = null;
        }
    }
    private void connect() {
        if (!active || selected == null) return;
        try {
            status("Connecting. Enter the stick PIN if Android asks.");
            gatt = selected.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE);
            handler.postDelayed(watchdog, 45000);
        } catch (SecurityException denied) {
            status("Bluetooth permission revoked");
        }
    }
    private final BluetoothGattCallback callbacks = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt link, int result, int state) {
            handler.post(() -> {
                if (gatt != link) return;
                if (result == 0 && state == BluetoothProfile.STATE_CONNECTED) {
                    if (!link.discoverServices()) lost("Service discovery failed");
                } else lost("Stick disconnected");
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt link, int code) {
            handler.post(() -> {
                if (gatt != link) return;
                BluetoothGattService service = link.getService(SERVICE);
                BluetoothGattCharacteristic data = service == null ? null : service.getCharacteristic(DATA);
                if (code == 0 && data != null) read(link, data);
                else lost("Expected stick service missing");
            });
        }
        @Override public void onCharacteristicRead(BluetoothGatt link, BluetoothGattCharacteristic c, byte[] value, int code) {
            receive(link, c, value.clone(), code);
        }
        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt link, BluetoothGattCharacteristic c, int code) {
            byte[] value = c.getValue();
            receive(link, c, value == null ? new byte[0] : value.clone(), code);
        }
    };
    private void receive(BluetoothGatt link, BluetoothGattCharacteristic characteristic, byte[] bytes, int code) {
        handler.post(() -> {
            if (link != gatt) return;
            if (code != BluetoothGatt.GATT_SUCCESS) {
                if (pairingAllowed && (code == 5 || code == 15)) {
                    if (selected.getBondState() == BluetoothDevice.BOND_NONE) selected.createBond();
                    handler.postDelayed(() -> read(link, characteristic), 1200);
                } else lost("Protected BLE read failed");
                return;
            }
            try {
                Packet packet = Packet.parse(bytes);
                if (selected.getBondState() != BluetoothDevice.BOND_BONDED) {
                    pairingAllowed = false;
                    lost("BLE bond required");
                    return;
                }
                pairingAllowed = false;
                handler.removeCallbacks(watchdog);
                packets.accept(packet);
                handler.postDelayed(() -> read(link, characteristic), 250);
            } catch (SecurityException denied) {
                lost("Bluetooth permission revoked");
            } catch (IllegalArgumentException badPacket) {
                lost("Unsupported stick protocol");
            }
        });
    }
    private void read(BluetoothGatt link, BluetoothGattCharacteristic c) {
        if (link != gatt) return;
        try {
            if (!link.readCharacteristic(c)) {
                lost("BLE read could not start");
                return;
            }
            if (!handler.hasCallbacks(watchdog)) handler.postDelayed(watchdog, 3000);
        } catch (SecurityException denied) {
            lost("BLE permission revoked");
        }
    }
    private final Runnable reconnect = this::connect;
    private void lost(String message) {
        closeGatt();
        status(message + ". Local stick warnings remain independent.");
        try {
            if (active && selected != null && selected.getBondState() == BluetoothDevice.BOND_BONDED)
                handler.postDelayed(reconnect, 2500);
        } catch (SecurityException denied) {
            status("Bluetooth permission revoked");
        }
    }
    void stop() {
        active = false;
        handler.removeCallbacks(reconnect);
        stopScan();
        closeGatt();
    }
    private void closeGatt() {
        handler.removeCallbacks(watchdog);
        BluetoothGatt old = gatt;
        gatt = null;
        if (old != null) {
            try { old.close(); } catch (SecurityException ignored) {}
        }
    }
}
