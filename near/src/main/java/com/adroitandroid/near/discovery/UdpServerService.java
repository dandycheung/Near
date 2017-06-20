package com.adroitandroid.near.discovery;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v4.util.ArrayMap;
import android.support.v4.util.ArraySet;
import android.util.Log;

import com.adroitandroid.near.model.Host;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;

/**
 * Created by pv on 20/06/17.
 */

public class UdpServerService extends Service {
    public static final String BUNDLE_COMMAND = "bundle_command";
    private static final String BUNDLE_IS_HOST_CLIENT = "bundle_host_is_client_too";
    public static final String COMMAND_START_SERVER = "start_server";
    public static final String COMMAND_STOP_SERVER = "stop_server";
    private UdpServerBinder mBinder;
    private ArrayMap<Host, StaleHostHandler> mHostHandlerMap = new ArrayMap<>();
    private Set<String> mCurrentHostIps = Collections.synchronizedSet(new ArraySet<String>());
    private ConnectivityChangeReceiver mConnectivityChangeReceiver;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (COMMAND_START_SERVER.equals(intent.getStringExtra(BUNDLE_COMMAND))) {
                startBroadcastListening(intent.getBooleanExtra(BUNDLE_IS_HOST_CLIENT, false));
            } else if (COMMAND_STOP_SERVER.equals(intent.getStringExtra(BUNDLE_COMMAND))) {
                UdpBroadcastListeningHandler.stopListeningForBroadcasts();
                stopSelf();
            }
        }

        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        initCurrentDeviceIps();

        IntentFilter connectivityChangeFilter = new IntentFilter();
        connectivityChangeFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        mConnectivityChangeReceiver = new ConnectivityChangeReceiver();
        registerReceiver(mConnectivityChangeReceiver, connectivityChangeFilter);
    }

    private void initCurrentDeviceIps() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            Set<String> updatedIps = new ArraySet<>();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();

                try {
                    if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                        continue;
                    }

                    Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                    while (inetAddresses.hasMoreElements()) {
                        InetAddress inetAddress = inetAddresses.nextElement();
                        updatedIps.add(inetAddress.getHostAddress());
                    }
                } catch (SocketException e) {
//                TODO: exception while getting current device IP!
                    e.printStackTrace();
                }
            }
            mCurrentHostIps.retainAll(updatedIps);
            mCurrentHostIps.addAll(updatedIps);
        } catch (SocketException e) {
//                TODO: exception while getting current device IP!
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d("UdpServerService", "destroying server service");
        unregisterReceiver(mConnectivityChangeReceiver);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        if (mBinder == null) {
            mBinder = new UdpServerBinder();
        }
        return mBinder;
    }

    private void startBroadcastListening(boolean isHostClientToo) {
        UdpBroadcastListeningHandler.startBroadcastListening(mHostHandlerMap, mCurrentHostIps, isHostClientToo);
    }

    private static class UdpBroadcastListeningHandler extends Handler {
        private static final int LISTEN = 5678;
        private static UdpBroadcastListeningHandler handler;
        private DatagramSocket mSocket;
        private UdpBroadcastListener mListener;
        private ArrayMap<Host, StaleHostHandler> mHostHandlerMap;
        private Set<String> mCurrentIps;
        private boolean isHostClientToo;

        UdpBroadcastListeningHandler(Looper looper) {
            super(looper);
        }

        static void startBroadcastListening(final ArrayMap<Host, StaleHostHandler> hostHandlerMap,
                                            final Set<String> currentHostIps, final boolean isHostClientToo) {
            HandlerThread handlerThread = new HandlerThread("ServerService") {
                @Override
                protected void onLooperPrepared() {
                    handler = new UdpBroadcastListeningHandler(getLooper());
                    handler.mHostHandlerMap = hostHandlerMap;
                    handler.mCurrentIps = currentHostIps;
                    handler.isHostClientToo = isHostClientToo;
                    handler.sendEmptyMessage(UdpBroadcastListeningHandler.LISTEN);
                }
            };
            handlerThread.start();
        }

        private static void setListener(UdpBroadcastListener listener) {
            if (handler != null) {
                handler.updateListenersTo(listener);
            }
        }

        private void updateListenersTo(UdpBroadcastListener listener) {
            mListener = listener;
            synchronized (this) {
                for (StaleHostHandler hostHandler : mHostHandlerMap.values()) {
                    hostHandler.setListener(listener);
                }
            }
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == LISTEN) {
                //Keep a socket open to listen to all the UDP traffic that is destined for this port
                try {
                    if (mSocket == null) {
                        mSocket = new DatagramSocket(8888, InetAddress.getByName("0.0.0.0"));
                        mSocket.setBroadcast(true);
                        mSocket.setSoTimeout(0); // infinitely wait for data
                    }

                    //Receive a packet
                    byte[] recvBuf = new byte[15000];
                    DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
                    DatagramSocket socket = mSocket;
                    socket.receive(packet);

                    //Packet received
                    Host host = new Host(packet.getAddress(), new String(packet.getData()).trim());

                    if (isHostClientToo || !mCurrentIps.contains(host.getHostAddress())) {
                        StaleHostHandler handler = mHostHandlerMap.get(host);
                        if (handler == null) {
                            handler = new StaleHostHandler(host, mHostHandlerMap, mListener);
                            synchronized (this) {
                                mHostHandlerMap.put(host, handler);
                            }
                            if (mListener != null) {
                                new Handler(Looper.getMainLooper()).post(new Runnable() {
                                    @Override
                                    public void run() {
                                        mListener.onHostsUpdate(mHostHandlerMap.keySet());
                                    }
                                });
                            }
                        } else if (hostNameChanged(host, mHostHandlerMap)) {
                            if (mListener != null) {
                                new Handler(Looper.getMainLooper()).post(new Runnable() {
                                    @Override
                                    public void run() {
                                        mListener.onHostsUpdate(mHostHandlerMap.keySet());
                                    }
                                });
                            }
                        }
                        handler.removeMessages(StaleHostHandler.STALE_HOST);
                        handler.sendEmptyMessageDelayed(StaleHostHandler.STALE_HOST, StaleHostHandler.STALE_INTERVAL);
                    }
                } catch (SocketException | UnknownHostException e) {
                    e.printStackTrace();
                    if (mSocket != null) {
                        mSocket.close();
                        mSocket = null;
                    }
//                    if (mListener != null) {
//                        new Handler(Looper.getMainLooper()).post(new Runnable() {
//                            @Override
//                            public void run() {
//                                mListener.onServerSetupFailed(e);
//                            }
//                        });
//                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    if (mListener != null) {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                mListener.onReceiveFailed();
                            }
                        });
                    }
                }
                if (getLooper().getThread().isAlive()) {
                    sendEmptyMessage(LISTEN);
                }
            }
        }

        private boolean hostNameChanged(Host updatedHost, ArrayMap<Host, StaleHostHandler> hostHandlerMap) {
            for (Host host : hostHandlerMap.keySet()) {
                if (updatedHost.equals(host) && !updatedHost.getName().equals(host.getName())) {
                    hostHandlerMap.put(updatedHost, hostHandlerMap.remove(host));
                    return true;
                }
            }
            return false;
        }

        static void stopListeningForBroadcasts() {
            if (handler != null) {
                handler.stop();
                handler = null;
            }
        }

        private void stop() {
            mListener = null;
            mSocket.close();
            mSocket = null;
            removeMessages(UdpBroadcastListeningHandler.LISTEN);
            getLooper().quitSafely();
        }
    }

    private static class StaleHostHandler extends Handler {
        private static final int STALE_HOST = 9284;
        private static final long STALE_INTERVAL = 10000;
        private final Host mHost;
        private final ArrayMap<Host, StaleHostHandler> mMap;
        private UdpBroadcastListener mListener;

        StaleHostHandler(Host host, ArrayMap<Host, StaleHostHandler> hostHandlerMap,
                         UdpBroadcastListener listener) {
            mHost = host;
            mMap = hostHandlerMap;
            mListener = listener;
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == STALE_HOST) {
                mMap.remove(mHost);
                if (mListener != null) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            mListener.onHostsUpdate(mMap.keySet());
                        }
                    });
                }
            }
        }

        void setListener(UdpBroadcastListener listener) {
            this.mListener = listener;
        }
    }

    public interface UdpBroadcastListener {
        void onServerSetupFailed(Throwable e);

        void onReceiveFailed();

        void onHostsUpdate(Set<Host> currentHosts);
    }

    public class UdpServerBinder extends Binder {

        public void setBroadcastListener(UdpBroadcastListener listener) {
            UdpBroadcastListeningHandler.setListener(listener);
        }

        public void stopBroadcastListening() {
            UdpBroadcastListeningHandler.stopListeningForBroadcasts();
        }
    }

    private class ConnectivityChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            initCurrentDeviceIps();
        }
    }
}