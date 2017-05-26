package com.liquidcode.jukevox.networking.Server;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.liquidcode.jukevox.util.BTMessages;
import com.liquidcode.jukevox.util.BTStates;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.UUID;

/**
 * Created by mikev on 5/23/2017.
 */

public class BluetoothServer {

    private static final String TAG = "BTServer";
    // Bluteooth variables for client/server
    private String LISTEN_NAME = "Jukevox";
    private UUID UUID_RFCOMM_JUKEVOX = UUID.fromString("8201b35e-2fbb-11e7-93ae-92361f002671");
    // our handler to send messages back to the UI thread for various actions
    private Handler m_uiHandler = null;
    // our bluetooth adapter
    private BluetoothAdapter m_btAdapter = null;
    // state variables
    // our connection state
    private int m_state;
    // our listening state
    private int m_listenState;
    // thread that will run to accept incoming connections
    private AcceptThread m_acceptThread = null;
    private HashMap<String, ConnectedThread> m_clientConnections = null;
    // the server socket
    private BluetoothServerSocket m_serverSocket = null;

    public BluetoothServer(Handler uiHandler) {
        m_uiHandler = uiHandler;
        m_btAdapter = BluetoothAdapter.getDefaultAdapter();
        m_state = BTStates.STATE_NONE;
        m_listenState = BTStates.STATE_NONE;
        m_clientConnections = new HashMap<>();
    }

    /**
     * Starts the thread that will listen for client connections
     */
    public synchronized void startServerListen() {
        // stop listening for connections if we were before
        stopServerListen();
        // start the accept thread here
        if(m_acceptThread == null) {
            m_acceptThread = new AcceptThread(true);
        }
        // start the thread
        m_acceptThread.start();
    }

    /**
     * Stops the listening thread
     */
    public synchronized  void stopServerListen() {
        // stop accepting connections
        if(m_acceptThread != null) {
            m_acceptThread.cancel();
            m_acceptThread = null;
        }
    }

    /**
     * Ends the accept (listening) thread and closes all opened client connections
     *
     */
    public synchronized void endAllConnections() {
        if (m_acceptThread != null) {
            m_acceptThread.cancel();
            m_acceptThread = null;
        }

        for(HashMap.Entry<String, ConnectedThread> clientConnection : m_clientConnections.entrySet()) {
            // close all connections and null out the thread
            clientConnection.getValue().cancel();
        }
        // clear the map
        m_clientConnections.clear();
        // update the states that we are no longer listening nor connected
        m_state = BTStates.STATE_NONE;
        m_listenState = BTStates.STATE_NONE;
    }

    public void sendDataToClients(byte[] out) {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        for(HashMap.Entry<String, ConnectedThread> clientConnection : m_clientConnections.entrySet()) {
            synchronized (this) {
                if (m_state != BTStates.STATE_CONNECTED) return;
                r = clientConnection.getValue();
            }
            // Perform the write unsynchronized
            r.write(out);
        }
    }


    private synchronized void connectToClient(BluetoothSocket socket, BluetoothDevice
            device, final String socketType) {
        Log.d(TAG, "connected, Socket Type:" + socketType);
        // Create a new thread for the incoming connection and start it
        // also add it to the hashmap so we can keep track of it
        ConnectedThread newClientThread = new ConnectedThread(socket, socketType);
        String clientName = device.getName();
        newClientThread.start();
        m_clientConnections.put(clientName, newClientThread);
        // Send the name of the connected device back to the UI Activity
        // This is the UI Handler from our ServerFragment that we need to update
        Message msg = m_uiHandler.obtainMessage(BTMessages.MESSAGE_CLIENT_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(BTMessages.CLIENT_NAME, clientName);
        msg.setData(bundle);
        m_uiHandler.sendMessage(msg);
        // now notify all connected clients that the room counter has changed
        byte[] outgoing = new byte[2];
        outgoing[0] = BTMessages.SM_CLIENTCOUNT;
        outgoing[1] = (byte)m_clientConnections.size();
        sendDataToClients(outgoing);
    }

    /**
     * Indicate that the connection was lost and notify the UI Activity.
     */
    private void disconnectClient(String clientName) {
        // cancel this connection thread and remove it
        if(m_clientConnections.containsKey(clientName)) {
            ConnectedThread clientThread = m_clientConnections.get(clientName);
            if(clientThread != null) {
                clientThread.cancel();
            }
            m_clientConnections.remove(clientName);
        }
        // if there are no clients changes the state to STATE_NONE
        if(m_clientConnections.isEmpty()) {
            m_state = BTStates.STATE_NONE;
        }
        // Send a failure message back to the Activity
        Message msg = m_uiHandler.obtainMessage(BTMessages.MESSAGE_USER_DISCONNECT);
        Bundle bundle = new Bundle();
        bundle.putString(BTMessages.CLIENT_NAME, clientName);
        msg.setData(bundle);
        m_uiHandler.sendMessage(msg);
        // now notify all connected clients that the room counter has changed
        byte[] outgoing = new byte[2];
        outgoing[0] = BTMessages.SM_CLIENTCOUNT;
        outgoing[1] = (byte)m_clientConnections.size();
        sendDataToClients(outgoing);
    }

    /**
     * This thread runs while listening for incoming connections. It behaves
     * like a server-side client. It runs until a connection is accepted
     * (or until cancelled).
     */
    private class AcceptThread extends Thread {
        private String mSocketType;

        public AcceptThread(boolean secure) {
            mSocketType = "Secure";

            // Create a new listening server socket
            try {
                m_serverSocket = m_btAdapter.listenUsingRfcommWithServiceRecord(LISTEN_NAME, UUID_RFCOMM_JUKEVOX);
            } catch (IOException e) {
                Log.e(TAG, "Socket Type: " + mSocketType + "listen() failed", e);
            }
            m_listenState = BTStates.STATE_LISTEN;
        }

        public void run() {
            Log.d(TAG, "Socket Type: " + mSocketType +
                    "BEGIN mAcceptThread" + this);
            setName("AcceptThread" + mSocketType);

            // our temp client socket
            BluetoothSocket clientSocket = null;
            // Listen to the server socket if we're not connected
            while (m_listenState != BTStates.STATE_NONE) {
                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    clientSocket = m_serverSocket.accept();
                } catch (IOException e) {
                    if(m_serverSocket != null) {
                        try {
                            m_serverSocket.close();
                            m_listenState = BTStates.STATE_NONE;
                        } catch (IOException ex) {
                            Log.e(TAG, "Failed to close BTServer Socket");
                        }
                    }
                    Log.e(TAG, "Socket Type: " + mSocketType + " accept() failed", e);
                    break;
                }

                // If a connection was accepted
                if (clientSocket != null) {
                    synchronized (BluetoothServer.this) {
                        switch (m_listenState) {
                            case BTStates.STATE_LISTEN:
                                // Situation normal. Start the connected thread.
                                connectToClient(clientSocket, clientSocket.getRemoteDevice(), mSocketType);
                                break;
                            case BTStates.STATE_NONE:
                                // Either not ready or already connected. Terminate new socket.
                                try {
                                    clientSocket.close();
                                } catch (IOException e) {
                                    Log.e(TAG, "Could not close unwanted socket", e);
                                }
                                break;
                        }
                    }
                    // set the clientsocket to null since we want to keep listening
                    synchronized (BluetoothServer.this) {
                        clientSocket = null;
                    }
                }
            }
            Log.i(TAG, "END mAcceptThread, socket Type: " + mSocketType);
        }

        public void cancel() {
            Log.d(TAG, "Socket Type" + mSocketType + " cancel " + this);
            try {
                if(m_serverSocket != null) {
                    m_serverSocket.close();
                }
                m_listenState = BTStates.STATE_NONE;
            } catch (IOException e) {
                Log.e(TAG, "Socket Type" + mSocketType + " close() of server failed", e);
            }
        }
    }

    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream m_inputStream;
        private final OutputStream m_outputStream;
        private final String m_deviceName;

        public ConnectedThread(BluetoothSocket socket, String socketType) {
            Log.d(TAG, "create ConnectedThread: " + socketType);
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }

            m_deviceName = socket.getRemoteDevice().getName();
            m_inputStream = tmpIn;
            m_outputStream = tmpOut;
            m_state = BTStates.STATE_CONNECTED;
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectedThread");
            byte[] buffer = new byte[2048];
            int bytes;

            // Keep listening to the InputStream while connected
            while (m_state == BTStates.STATE_CONNECTED) {
                try {
                    // Read from the InputStream
                    bytes = m_inputStream.read(buffer);

                    // Send the obtained bytes to the UI Activity
                    m_uiHandler.obtainMessage(BTMessages.MESSAGE_READ, bytes, -1, buffer)
                            .sendToTarget();
                } catch (IOException e) {
                    Log.e(TAG, "disconnected", e);
                    disconnectClient(m_deviceName);
                    break;
                }
            }
        }

        /**
         * Write to the connected OutStream.
         *
         * @param buffer The bytes to write
         */
        public void write(byte[] buffer) {
            try {
                m_outputStream.write(buffer);
                // Share the sent message back to the UI Activity
                m_uiHandler.obtainMessage(BTMessages.MESSAGE_WRITE, -1, buffer.length, buffer)
                        .sendToTarget();
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }

        public void cancel() {
            try {
                // close the input/output stream first
                m_inputStream.close();
                m_outputStream.close();
                // now close the socket
                mmSocket.close();
                m_state = BTStates.STATE_NONE;
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }

}
