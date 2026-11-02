package com.viby.core.download;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Desktop replacement for Android's ConnectivityManager callback: periodically checks that
 * YouTube is reachable and reports changes. Downloads wait instead of failing while offline.
 */
public class NetworkMonitor {

    public interface Listener {
        void onNetworkChanged(boolean available);
    }

    private static final String HOST = "www.youtube.com";
    private static final int PORT = 443;
    private static final int TIMEOUT_MS = 3_000;
    private static final long ONLINE_INTERVAL_MS = 10_000;
    private static final long OFFLINE_INTERVAL_MS = 2_000;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean available = true;
    private volatile boolean running;

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public boolean isAvailable() {
        return available;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        Thread thread = new Thread(() -> {
            while (running) {
                setAvailable(probe());
                try {
                    Thread.sleep(available ? ONLINE_INTERVAL_MS : OFFLINE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "viby-network");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
    }

    /** Called by the polling thread; also lets callers report a definite failure early. */
    protected void setAvailable(boolean value) {
        boolean changed = available != value;
        available = value;
        if (changed) {
            listeners.forEach(listener -> listener.onNetworkChanged(value));
        }
    }

    protected boolean probe() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Always online; for tests and environments where probing is not wanted. */
    public static NetworkMonitor alwaysOnline() {
        return new NetworkMonitor() {
            @Override
            public synchronized void start() {
            }

            @Override
            protected boolean probe() {
                return true;
            }
        };
    }
}
