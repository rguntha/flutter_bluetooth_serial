package io.github.edufolly.flutterbluetoothserial;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.Arrays;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

/// Universal Bluetooth serial connection class (for Java)
public abstract class BluetoothConnection
{
    private static final String TAG = "BluetoothConnection";
    protected static final UUID DEFAULT_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Fallback RFCOMM channels to try if default method fails
    private static final int[] FALLBACK_CHANNELS = {1, 2, 3, 4, 5};

    protected BluetoothAdapter bluetoothAdapter;

    protected ConnectionThread connectionThread = null;

    // Track the socket separately for force close scenarios
    protected BluetoothSocket currentSocket = null;

    public boolean isConnected() {
        return connectionThread != null && connectionThread.requestedClosing != true;
    }



    public BluetoothConnection(BluetoothAdapter bluetoothAdapter) {
        this.bluetoothAdapter = bluetoothAdapter;
    }



    /// Connects to given device by hardware address
    public void connect(String address, UUID uuid) throws IOException {
        if (isConnected()) {
            throw new IOException("already connected");
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            throw new IOException("device not found");
        }

        // Cancel discovery before attempting to connect
        bluetoothAdapter.cancelDiscovery();

        BluetoothSocket socket = null;
        IOException lastException = null;

        // First, try the standard method
        try {
            Log.d(TAG, "Attempting connection using createRfcommSocketToServiceRecord");
            socket = device.createRfcommSocketToServiceRecord(uuid);
            if (socket != null) {
                socket.connect();
                Log.d(TAG, "Connected successfully using standard method");
            }
        } catch (IOException e) {
            Log.w(TAG, "Standard connection method failed: " + e.getMessage());
            lastException = e;
            // Close the socket if it was created before setting to null
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
            socket = null;
        }

        // If standard method failed, try reflection-based fallback
        if (socket == null || !socket.isConnected()) {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }

            for (int channel : FALLBACK_CHANNELS) {
                try {
                    Log.d(TAG, "Attempting fallback connection on channel " + channel);
                    socket = createRfcommSocketByReflection(device, channel);
                    if (socket != null) {
                        socket.connect();
                        if (socket.isConnected()) {
                            Log.d(TAG, "Connected successfully using fallback on channel " + channel);
                            break;
                        } else {
                            // Socket created but not connected - close it
                            Log.w(TAG, "Socket created on channel " + channel + " but not connected, closing");
                            try { socket.close(); } catch (Exception ignored) {}
                            socket = null;
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Fallback connection on channel " + channel + " failed: " + e.getMessage());
                    lastException = new IOException("Fallback connection failed", e);
                    if (socket != null) {
                        try { socket.close(); } catch (Exception ignored) {}
                    }
                    socket = null;
                }
            }
        }

        if (socket == null || !socket.isConnected()) {
            throw lastException != null ? lastException : new IOException("socket connection not established");
        }

        currentSocket = socket;
        connectionThread = new ConnectionThread(socket);
        connectionThread.start();
    }

    /// Creates an RFCOMM socket using reflection (fallback method)
    private BluetoothSocket createRfcommSocketByReflection(BluetoothDevice device, int channel) throws IOException {
        try {
            Method method = device.getClass().getMethod("createRfcommSocket", new Class[] { int.class });
            return (BluetoothSocket) method.invoke(device, channel);
        } catch (Exception e) {
            throw new IOException("Failed to create socket via reflection: " + e.getMessage(), e);
        }
    }

    /// Connects to given device by hardware address (default UUID used)
    public void connect(String address) throws IOException {
        connect(address, DEFAULT_UUID);
    }
    
    /// Disconnects current session (ignore if not connected)
    public void disconnect() {
        Log.d(TAG, "Disconnecting...");
        if (connectionThread != null) {
            connectionThread.cancel();
            connectionThread = null;
        }
        currentSocket = null;
        Log.d(TAG, "Disconnected");
    }

    /// Force disconnects by closing the socket directly
    /// Use this when normal disconnect doesn't work (e.g., device went out of range)
    public void forceDisconnect() {
        Log.d(TAG, "Force disconnecting...");

        // First, close the socket directly to unblock any pending I/O
        if (currentSocket != null) {
            try {
                currentSocket.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing socket during force disconnect: " + e.getMessage());
            }
            currentSocket = null;
        }

        // Then clean up the thread
        if (connectionThread != null) {
            connectionThread.forceCancel();
            connectionThread = null;
        }

        Log.d(TAG, "Force disconnected");
    }

    /// Writes to connected remote device 
    public void write(byte[] data) throws IOException {
        if (!isConnected()) {
            throw new IOException("not connected");
        }

        connectionThread.write(data);
    }

    /// Callback for reading data.
    protected abstract void onRead(byte[] data);

    /// Callback for disconnection.
    protected abstract void onDisconnected(boolean byRemote);

    /// Thread to handle connection I/O
    private class ConnectionThread extends Thread  {
        private final BluetoothSocket socket;
        private final InputStream input;
        private final OutputStream output;
        private volatile boolean requestedClosing = false;

        ConnectionThread(BluetoothSocket socket) {
            this.socket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "Error getting streams: " + e.getMessage());
                e.printStackTrace();
            }

            this.input = tmpIn;
            this.output = tmpOut;
        }

        /// Thread main code
        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;

            Log.d(TAG, "ConnectionThread started");

            while (!requestedClosing) {
                try {
                    bytes = input.read(buffer);
                    if (bytes == -1) {
                        // End of stream - remote device closed connection
                        Log.d(TAG, "End of stream reached");
                        break;
                    }
                    onRead(Arrays.copyOf(buffer, bytes));
                } catch (IOException e) {
                    // `input.read` throws when closed by remote device or socket is closed
                    Log.d(TAG, "Read exception (connection likely closed): " + e.getMessage());
                    break;
                }
            }

            Log.d(TAG, "ConnectionThread exiting read loop, cleaning up...");

            // Close streams in the correct order:
            // 1. Close input stream first to unblock any pending reads
            if (input != null) {
                try {
                    input.close();
                    Log.d(TAG, "Input stream closed");
                } catch (Exception e) {
                    Log.w(TAG, "Error closing input stream: " + e.getMessage());
                }
            }

            // 2. Flush and close output stream
            if (output != null) {
                try {
                    output.flush();
                } catch (Exception e) {
                    Log.w(TAG, "Error flushing output stream: " + e.getMessage());
                }
                try {
                    output.close();
                    Log.d(TAG, "Output stream closed");
                } catch (Exception e) {
                    Log.w(TAG, "Error closing output stream: " + e.getMessage());
                }
            }

            // 3. Close socket
            if (socket != null) {
                try {
                    socket.close();
                    Log.d(TAG, "Socket closed");
                } catch (Exception e) {
                    Log.w(TAG, "Error closing socket: " + e.getMessage());
                }
            }

            // Callback on disconnected, with information which side is closing
            boolean byRemote = !requestedClosing;
            Log.d(TAG, "Calling onDisconnected, byRemote=" + byRemote);
            onDisconnected(byRemote);

            // Mark as closed
            requestedClosing = true;
        }

        /// Writes to output stream
        public void write(byte[] bytes) {
            try {
                output.write(bytes);
            } catch (IOException e) {
                Log.e(TAG, "Write error: " + e.getMessage());
                e.printStackTrace();
            }
        }

        /// Stops the thread, disconnects gracefully
        public void cancel() {
            if (requestedClosing) {
                Log.d(TAG, "Already closing, skipping cancel");
                return;
            }
            requestedClosing = true;
            Log.d(TAG, "Cancel requested");

            // Flush output buffers before closing
            if (output != null) {
                try {
                    output.flush();
                } catch (Exception e) {
                    Log.w(TAG, "Error flushing during cancel: " + e.getMessage());
                }
            }

            // Close the connection socket with a longer delay to ensure cleanup
            if (socket != null) {
                try {
                    // Give time for data to be sent and Bluetooth stack to process
                    Thread.sleep(500);
                    socket.close();
                    Log.d(TAG, "Socket closed during cancel");
                } catch (Exception e) {
                    Log.w(TAG, "Error closing socket during cancel: " + e.getMessage());
                }
            }
        }

        /// Force cancels the thread without waiting
        public void forceCancel() {
            Log.d(TAG, "Force cancel requested");
            requestedClosing = true;

            // Close socket immediately to unblock I/O
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception e) {
                    Log.w(TAG, "Error closing socket during force cancel: " + e.getMessage());
                }
            }

            // Interrupt the thread if it's blocked
            try {
                this.interrupt();
            } catch (Exception e) {
                Log.w(TAG, "Error interrupting thread: " + e.getMessage());
            }
        }
    }
}
