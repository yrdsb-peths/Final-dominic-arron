package com.leaf.game;

import java.io.*;
import java.net.*;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class NetworkSession {

    private static final int PORT = 25565;

    public volatile float   remoteX, remoteY, remoteZ;
    public volatile float   remoteYaw, remotePitch;
    public volatile boolean connected = false;

    // --- SEED SYNCING ---
    public volatile boolean seedReceived = false;
    public volatile long    newSeed = 0;

    private final Queue<int[]>  incomingBreaks = new ConcurrentLinkedQueue<>();
    private final Queue<int[]>  incomingPlaces = new ConcurrentLinkedQueue<>();
    private final Queue<String> incomingChats  = new ConcurrentLinkedQueue<>();
    private final Queue<int[]>  incomingPickups = new ConcurrentLinkedQueue<>();

    private final boolean isHost;
    private final String  hostIp;
    private PrintWriter   out;
    private final Object  writeLock = new Object();

    public NetworkSession(boolean isHost, String hostIp) {
        this.isHost = isHost;
        this.hostIp = hostIp;
    }

    public void start() {
        Thread t = new Thread(this::runNetworkLoop, "network-thread");
        t.setDaemon(true);
        t.start();
    }

    private void runNetworkLoop() {
        try {
            Socket socket = isHost ? waitForConnection() : connectToHost();
            if (socket == null) return;

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            synchronized (writeLock) {
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
            }

            connected = true;
            System.out.println("[Net] Connected!");

            // If we are the host, immediately send our world seed to the client!
            if (isHost) {
                send("SEED:" + GameConfig.seed);
            }

            String line;
            while ((line = in.readLine()) != null) {
                handleIncoming(line);
            }

        } catch (IOException e) {
            System.err.println("[Net] Connection error: " + e.getMessage());
        }
        connected = false;
    }

    private Socket waitForConnection() throws IOException {
        System.out.println("[Net] Hosting on port " + PORT + " — waiting for friend...");
        ServerSocket serverSocket = new ServerSocket(PORT, 1);
        return serverSocket.accept();
    }

    private Socket connectToHost() {
        System.out.println("[Net] Connecting to " + hostIp + ":" + PORT + " ...");
        try {
            return new Socket(hostIp, PORT);
        } catch (IOException e) {
            return null;
        }
    }

    private void handleIncoming(String line) {
        if (line.startsWith("POS:")) {
            String[] p = line.substring(4).split(",");
            if (p.length < 5) return;
            try {
                remoteX     = Float.parseFloat(p[0]);
                remoteY     = Float.parseFloat(p[1]);
                remoteZ     = Float.parseFloat(p[2]);
                remoteYaw   = Float.parseFloat(p[3]);
                remotePitch = Float.parseFloat(p[4]);
            } catch (NumberFormatException ignored) {}

        } else if (line.startsWith("SEED:")) {
            try {
                newSeed = Long.parseLong(line.substring(5));
                seedReceived = true;
            } catch (NumberFormatException ignored) {}

        } else if (line.startsWith("BREAK:")) {
            String[] p = line.substring(6).split(",");
            try { incomingBreaks.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])}); } catch (Exception ignored) {}
        } else if (line.startsWith("PLACE:")) {
            String[] p = line.substring(6).split(",");
            try { incomingPlaces.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])}); } catch (Exception ignored) {}
        } else if (line.startsWith("CHAT:")) {
            incomingChats.add(line.substring(5));
        } else if (line.startsWith("PICKUP:")) {
            String[] p = line.substring(7).split(",");
            try {
                incomingPickups.add(new int[]{
                        Integer.parseInt(p[0]),
                        Integer.parseInt(p[1]),
                        Integer.parseInt(p[2])
                });
            } catch (Exception ignored) {}
        }
    } // <── This single bracket now correctly closes the handleIncoming method at the very end!

    public void sendPosition(float x, float y, float z, float yaw, float pitch) { send("POS:" + x + "," + y + "," + z + "," + yaw + "," + pitch); }
    public void sendBreak(int x, int y, int z) { send("BREAK:" + x + "," + y + "," + z); }
    public void sendPlace(int x, int y, int z, Block block) { send("PLACE:" + x + "," + y + "," + z + "," + block.ordinal()); }
    public void sendChat(String message) { send("CHAT:" + message); }

    private void send(String message) {
        synchronized (writeLock) { if (out != null) out.println(message); }
    }

    public int[] pollBreak() { return incomingBreaks.poll(); }
    public int[] pollPlace() { return incomingPlaces.poll(); }
    public String pollChat() { return incomingChats.poll(); }
    public void sendPickup(int x, int y, int z) { send("PICKUP:" + x + "," + y + "," + z); }
    public int[] pollPickup() { return incomingPickups.poll(); }
}