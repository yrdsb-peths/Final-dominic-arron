package com.leaf.game.net;

import com.leaf.game.core.GameConfig;
import com.leaf.game.world.Block;

import java.io.*;
import java.net.*;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class NetworkSession {

    private static final int PORT = 25566;

    public volatile float   remoteX, remoteY, remoteZ;
    public volatile float   remoteYaw, remotePitch, remoteRoll;
    public volatile int     remoteState = 0;
    public volatile boolean remoteHooked = false;
    public volatile float   remoteHookX, remoteHookY, remoteHookZ;

    public volatile boolean connected = false;
    public volatile boolean seedReceived = false;
    public volatile long    newSeed = 0;

    private final Queue<int[]>  incomingBreaks  = new ConcurrentLinkedQueue<>();
    private final Queue<int[]>  incomingPlaces  = new ConcurrentLinkedQueue<>();
    private final Queue<String> incomingChats   = new ConcurrentLinkedQueue<>();
    private final Queue<int[]>  incomingPickups = new ConcurrentLinkedQueue<>();
    private final Queue<int[]>  incomingCraters = new ConcurrentLinkedQueue<>();

    private final boolean isHost;
    private final String  hostIp;
    private DataOutputStream out;
    private final Object writeLock = new Object();

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

            // CRITICAL SYNC FIX: Disable TCP batching to prevent network stutter
            socket.setTcpNoDelay(true);

            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            synchronized (writeLock) {
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            }

            connected = true;
            System.out.println("[Net] Connected via Binary Protocol!");

            if (isHost) sendSeed(GameConfig.seed);

            // Binary Read Loop (Zero String-GC overhead)
            while (true) {
                byte packetId = in.readByte();
                handleIncoming(packetId, in);
            }

        } catch (EOFException e) {
            System.out.println("[Net] Disconnected.");
        } catch (IOException e) {
            System.err.println("[Net] Connection error: " + e.getMessage());
        }
        connected = false;
    }

    private Socket waitForConnection() throws IOException {
        System.out.println("[Net] Hosting on port " + PORT + " — waiting for friend...");
        try (ServerSocket serverSocket = new ServerSocket(PORT, 1)) {
            return serverSocket.accept();
        }
    }

    private Socket connectToHost() {
        System.out.println("[Net] Connecting to " + hostIp + ":" + PORT + " ...");
        try { return new Socket(hostIp, PORT); } catch (IOException e) { return null; }
    }

    private void handleIncoming(byte id, DataInputStream in) throws IOException {
        switch (id) {
            case 1: // POS
                remoteX = in.readFloat(); remoteY = in.readFloat(); remoteZ = in.readFloat();
                remoteYaw = in.readFloat(); remotePitch = in.readFloat(); remoteRoll = in.readFloat();
                break;
            case 2: // STATE
                remoteState = in.readByte();
                break;
            case 3: // GRAPPLE
                remoteHooked = in.readBoolean();
                remoteHookX = in.readFloat(); remoteHookY = in.readFloat(); remoteHookZ = in.readFloat();
                break;
            case 4: // BREAK
                incomingBreaks.add(new int[]{in.readInt(), in.readInt(), in.readInt()});
                break;
            case 5: // PLACE
                incomingPlaces.add(new int[]{in.readInt(), in.readInt(), in.readInt(), in.readInt()});
                break;
            case 6: // CHAT
                incomingChats.add(in.readUTF());
                break;
            case 7: // PICKUP
                incomingPickups.add(new int[]{in.readInt(), in.readInt(), in.readInt()});
                break;
            case 8: // CRATER
                incomingCraters.add(new int[]{in.readInt(), in.readInt(), in.readInt(), in.readInt()});
                break;
            case 9: // SEED
                newSeed = in.readLong();
                seedReceived = true;
                break;
        }
    }

    // --- High-Performance Binary Senders ---
    public void sendPosition(float x, float y, float z, float yaw, float pitch, float roll) {
        synchronized (writeLock) {
            try { if (out == null) return;
                out.writeByte(1); out.writeFloat(x); out.writeFloat(y); out.writeFloat(z);
                out.writeFloat(yaw); out.writeFloat(pitch); out.writeFloat(roll); out.flush();
            } catch (IOException ignored) {}
        }
    }

    public void sendState(int state) {
        synchronized (writeLock) {
            try { if (out == null) return; out.writeByte(2); out.writeByte((byte)state); out.flush(); } catch (IOException ignored) {}
        }
    }

    public void sendGrapple(boolean hooked, float hx, float hy, float hz) {
        synchronized (writeLock) {
            try { if (out == null) return; out.writeByte(3); out.writeBoolean(hooked);
                out.writeFloat(hx); out.writeFloat(hy); out.writeFloat(hz); out.flush();
            } catch (IOException ignored) {}
        }
    }

    public void sendBreak(int x, int y, int z) {
        synchronized (writeLock) { try { if (out == null) return; out.writeByte(4); out.writeInt(x); out.writeInt(y); out.writeInt(z); out.flush(); } catch (IOException ignored) {} }
    }
    public void sendPlace(int x, int y, int z, Block b) {
        synchronized (writeLock) { try { if (out == null) return; out.writeByte(5); out.writeInt(x); out.writeInt(y); out.writeInt(z); out.writeInt(b.ordinal()); out.flush(); } catch (IOException ignored) {} }
    }
    public void sendChat(String msg) {
        synchronized (writeLock) { try { if (out == null) return; out.writeByte(6); out.writeUTF("CHAT:" + msg); out.flush(); } catch (IOException ignored) {} }
    }
    public void sendPickup(int x, int y, int z) {
        synchronized (writeLock) { try { if (out == null) return; out.writeByte(7); out.writeInt(x); out.writeInt(y); out.writeInt(z); out.flush(); } catch (IOException ignored) {} }
    }
    public void sendCrater(int x, int y, int z, int r) {
        synchronized (writeLock) { try { if (out == null) return; out.writeByte(8); out.writeInt(x); out.writeInt(y); out.writeInt(z); out.writeInt(r); out.flush(); } catch (IOException ignored) {} }
    }
    private void sendSeed(long seed) {
        synchronized (writeLock) { try { if (out == null) return; out.writeByte(9); out.writeLong(seed); out.flush(); } catch (IOException ignored) {} }
    }

    // Queue Pollers (unchanged)
    public int[] pollBreak() { return incomingBreaks.poll(); }
    public int[] pollPlace() { return incomingPlaces.poll(); }
    public String pollChat() { return incomingChats.poll(); }
    public int[] pollPickup() { return incomingPickups.poll(); }
    public int[] pollCrater() { return incomingCraters.poll(); }
    public boolean isHost() {return isHost; }
}