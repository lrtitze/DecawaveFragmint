package com.example.mysimpletest;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class USBSerialConnector implements SerialInputOutputManager.Listener {

    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    private static final String TAG = "USBSerialConnector";

    private static boolean hasPermission = false;

    private SerialInputOutputManager serialIOManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private UsbManager usbManager;
    private UsbSerialPort serialPort;
    private USBSerialListener usbSerialListener;
    private Handler handlerTimeOut;
    private Context mContext;

    private static USBSerialConnector instance = null;

    private Runnable timeOutRunnable = new Runnable() {
        @Override
        public void run() {
            usbSerialListener.onDataReceived(null);
        }
    };

    public static USBSerialConnector getInstance() {
        if (instance == null) {
            instance = new USBSerialConnector();
        }
        return instance;
    }

    public void init(Context context, final int baudRate) {
        mContext = context;
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);

        /**
         * Receiver to USB Permission
         */
        BroadcastReceiver usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_USB_PERMISSION.equals(action)) {
                    synchronized (this) {
                        SystemClock.sleep(1000);
                        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
                        if (availableDrivers != null && !availableDrivers.isEmpty()) {
                            UsbSerialDriver driver = availableDrivers.get(0);
                            List<UsbSerialPort> ports = driver.getPorts();
                            if (!ports.isEmpty()) {
                                serialPort = ports.get(0);
                                context.unregisterReceiver(this);
                                hasPermission = true;
                                new SetUsbSerialDriver().execute(baudRate);
                            }
                        }
                    }
                }
            }
        };

        PendingIntent mPermissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        context.registerReceiver(usbReceiver, filter);

        SystemClock.sleep(1000);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (availableDrivers != null && !availableDrivers.isEmpty()) {
            // Open a connection to the first available driver.
            UsbSerialDriver driver = availableDrivers.get(0);
            List<UsbSerialPort> ports = driver.getPorts();

            // Read some data! Most have just one port (port 0).
            if (!ports.isEmpty()) {
                serialPort = ports.get(0);
                Log.d(TAG, "Port: " + serialPort);
                if (!hasPermission) {
                    usbManager.requestPermission(serialPort.getDriver().getDevice(), mPermissionIntent);
                } else {
                    new SetUsbSerialDriver().execute(baudRate);
                }
            } else {
                Log.d(TAG, "USB Serial device not found");
                usbSerialListener.onDeviceReady(ResponseStatus.ERROR);
            }
        } else {
            onDeviceStateChange(ResponseStatus.ERROR);
        }

        registerOnDeviceDisconnected(context);
    }

    public void pausedActivity() {
        stopIOManager();
        if (serialPort != null) {
            try {
                serialPort.close();
            } catch (IOException e) {
                usbSerialListener.onErrorReceived(Utilities.getStackTrace(e));
            }
            serialPort = null;
        }
    }

    private void stopIOManager() {
        if (serialIOManager != null) {
            serialIOManager.stop();
            serialIOManager = null;
        }
    }

    private void startIOManager() {
        if (serialPort != null) {
            serialIOManager = new SerialInputOutputManager(serialPort, this);
            executor.submit(serialIOManager);
        }
    }

    private void onDeviceStateChange(ResponseStatus responseStatus) {
        stopIOManager();
        startIOManager();
        usbSerialListener.onDeviceReady(responseStatus);
    }

    private void registerOnDeviceDisconnected(Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
                    usbSerialListener.onDeviceDisconnected();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        context.registerReceiver(receiver, filter);
    }

    public void setUsbSerialListener(USBSerialListener usbSerialListener) {
        this.usbSerialListener = usbSerialListener;
    }

    private void removeTimeOutCallbacks() {
        if (handlerTimeOut != null) {
            handlerTimeOut.removeCallbacks(timeOutRunnable);
        }
    }

    public void write(byte[] data, int timeoutMillis) {
        handlerTimeOut = new Handler();
        if (serialIOManager != null) {
            serialIOManager.writeAsync(data);
            handlerTimeOut.postDelayed(timeOutRunnable, timeoutMillis);
        } else {
            usbSerialListener.onErrorReceived("Null SerialIOManager");
        }
    }

    public void writeAsync(byte[] data){
        if (serialIOManager != null) {
            serialIOManager.writeAsync(data);
        } else {
            usbSerialListener.onErrorReceived("Null SerialIOManager");
        }
    }

    @Override
    public void onNewData(final byte[] data) {
        //removeTimeOutCallbacks();
        Log.d(TAG, Utilities.bytesToString(data));
        ((Activity) mContext).runOnUiThread(new Runnable() {
            public void run() {
                usbSerialListener.onDataReceived(data);
            }
        });
    }

    @Override
    public void onRunError(Exception e) {
        //removeTimeOutCallbacks();
        usbSerialListener.onErrorReceived("onRunError: " + Utilities.getStackTrace(e));
    }

    private class SetUsbSerialDriver extends AsyncTask<Integer, Void, Boolean> {
        @Override
        protected Boolean doInBackground(Integer... params) {
            try {
                UsbDeviceConnection connection = usbManager.openDevice(serialPort.getDriver().getDevice());
                if (connection == null) {
                    // You probably need to
                    // callUsbManager.requestPermission(driver.getDevice(),
                    // ..)
                    Log.d(TAG, "Opening device failed");
                    return false;
                } else {
                    try {
                        serialPort.open(connection);
                        serialPort.setParameters(params[0], 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                        Log.d(TAG, "Serial device: " + serialPort.getClass().getSimpleName());
                        return true;
                    } catch (IOException e) {
                        Log.d(TAG, "Error setting up device: " + Utilities.getStackTrace(e));
                        pausedActivity();
                        serialPort = null;
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Error setting up device: " + Utilities.getStackTrace(e));
                pausedActivity();
            }

            return false;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            onDeviceStateChange(result ? ResponseStatus.SUCCESS : ResponseStatus.ERROR);
        }
    }
}