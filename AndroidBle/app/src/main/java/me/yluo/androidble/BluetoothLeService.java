package me.yluo.androidble;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Stack;
import java.util.UUID;


public class BluetoothLeService extends Service {
    private final static String TAG = BluetoothLeService.class.getSimpleName();
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    BluetoothGattCharacteristic serialCharacteristic;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;
    private Queue<SendData> sendQueue = new ArrayDeque<>(16);
    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;
    private boolean sending = false; // 正在发送

    public final static String ACTION_GATT_CONNECTED = "yluo.me.ble.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED = "yluo.me.ble.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_DATA_AVAILABLE = "yluo.me.ble.ACTION_DATA_AVAILABLE";
    public final static String ACTION_SERVICE_DISCOVERED = "yluo.me.ble.ACTION_SERVICE_DISCOVERED";
    public final static String EXTRA_DATA = "yluo.me.ble.EXTRA_DATA";

    public final static UUID UUID_SERIAL_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    public final static UUID UUID_SERIAL_Characteristic = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");


    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_GATT_CONNECTED;
                mConnectionState = STATE_CONNECTED;
                broadcastUpdate(intentAction);
                Log.i(TAG, "Connected to GATT Attempting to start service discovery:" + mBluetoothGatt.discoverServices());
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED;
                mConnectionState = STATE_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server.");
                broadcastUpdate(intentAction);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            //这个函数调用过后才能发 数据
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = mBluetoothGatt.getService(UUID_SERIAL_SERVICE);
                if (service != null) {
                    serialCharacteristic = service.getCharacteristic(UUID_SERIAL_Characteristic);
                    if (serialCharacteristic != null) {
                        mBluetoothGatt.setCharacteristicNotification(serialCharacteristic, true);
                    }
                }

                final Intent intent = new Intent(ACTION_SERVICE_DISCOVERED);
                intent.putExtra(EXTRA_DATA, serialCharacteristic != null); //true 可以发送
                sendBroadcast(intent);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            //写数据结果
            String name = GattUuids.lookup(characteristic.getUuid().toString(), "NAN Characteristic");
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "onCharacteristicWrite success " + name);
                if (sendQueue.peek().haveMoreData()) { //包还没发完
                    Log.i(TAG, "send next packet");
                    writeData(sendQueue.peek().getData());
                } else {
                    sendQueue.poll();//出队
                    if (sendQueue.size() > 0) {//还有数据 继续
                        writeData(sendQueue.peek().getData());
                    } else {
                        sending = false;
                    }
                }
            } else {
                Log.w(TAG, "onCharacteristicWrite fail" + name);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            //从串口读出消息
            if (characteristic.getUuid().toString().equals(UUID_SERIAL_Characteristic.toString())) {
                broadcastData(ACTION_DATA_AVAILABLE, characteristic.getValue());
            }
        }
    };

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

    private byte[] tempData = new byte[2048];
    private int position = 0;

    private void broadcastData(final String action, final byte[] data) {
        final Intent intent = new Intent(action);
        if (data != null && data.length > 1) {
            if (position + data.length > tempData.length) {
                //满了 直接丢弃
                position = 0;
            }

            System.arraycopy(data, 0, tempData, position, data.length);
            position += data.length;
            if (tempData[position - 1] == 10 && tempData[position - 2] == 13) {//结尾
                intent.putExtra(EXTRA_DATA, new String(tempData, 0, position - 2));
                position = 0;
                sendBroadcast(intent);
            } else { //没有结束
                //do nothing
            }
        }
    }

    public class LocalBinder extends Binder {
        BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i("BluetoothLeService", "onUnbind BluetoothGatt");
        if (mBluetoothGatt != null) {
            mBluetoothGatt.close();
            mBluetoothGatt = null;
        }
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();

    public boolean initialize() {
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    @Override
    public void onDestroy() {
        Log.i("BluetoothLeService", "destroy BluetoothGatt");
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
        super.onDestroy();
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     */
    public boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress) && mBluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect()) {
                mConnectionState = STATE_CONNECTING;
                return true;
            } else {
                return false;
            }
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }

        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        mBluetoothDeviceAddress = address;
        mConnectionState = STATE_CONNECTING;
        return true;
    }

    public void disconnect() {
        if (mBluetoothGatt != null)
            mBluetoothGatt.disconnect();
    }

    //year 0-99 month 1-12
    //week 1-7
    public void setTime(int year, int month, int day, int week,
                        int hour, int minute, int second) {
        if (year < 100 && year > 0 && month > 0 && month < 13 && day > 0 && day < 32 &&
                week > 0 && week < 8 && hour >= 0 && hour < 25 && minute >= 0 && minute < 61
                && second >= 0 && second < 61) {
            sendData(DataType.SET_TIME, "100" + " " + year + " " + month + " " + day + " " +
                    week + " " + hour + " " + minute + " " + second);
        } else {
            throw new IllegalArgumentException("时间格式错误.");
        }
    }

    public void setRgb(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        Log.d(TAG, "set rgb " + r + " " + g + " " + b);
        sendData(DataType.SET_RGB, DataType.SET_RGB.toString() + " " + r + " " + g + " " + b);
    }


    public boolean sendData(DataType type, String data) {
        if (serialCharacteristic == null) return false;

        if (!data.endsWith("\n")) {
            data = data + "\n";
        }


        sendQueue.add(new SendData(type, data));
        if (!sending) { //没在发送
            byte[] b = sendQueue.peek().getData();
            writeData(b);
        }
        //正在发送
        return true;
    }

    //真正的写数据
    private void writeData(byte[] data) {
        sending = true;
        Log.d(TAG, "write data to ble :" + new String(data));
        serialCharacteristic.setValue(data);
        mBluetoothGatt.writeCharacteristic(serialCharacteristic);
        mBluetoothGatt.readCharacteristic(serialCharacteristic);
    }
}