package com.leaf.game;

import java.io.*;
import java.net.*;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Handles ALL multiplayer networking for both the host and the joining friend.
 * Replaces the old GameServer.java and GameClient.java — delete those.
 *
 * HOW TO USE:
 *   Host:   NetworkSession net = new NetworkSession(true,  null);
 *   Friend: NetworkSession net = new NetworkSession(false, "192.168.x.x");
 *   Then:   net.start();
 *
 * THREAD MODEL:
 *   The game runs on the main thread.
 *   The network runs on a background thread.
 *   They share data through:
 *     - 'volatile' fields for the remote player's position (safe single-value reads)
 *     - ConcurrentLinkedQueue for events like block breaks (safe multi-item streams)
 *
 * PROTOCOL — plain text, one message per line:
 *   POS:x,y,z,yaw,pitch       — player position update (sent every frame)
 *   BREAK:x,y,z               — a block was broken
 *   PLACE:x,y,z,blockOrdinal  — a block was placed (ordinal = Block.values() index)
 *   CHAT:message              — a chat message
 */
public class NetworkSession {

    private static final int PORT = 49152;

    // -----------------------------------------------------------------------
    // Remote player state — written by network thread, read by game loop.
    // 'volatile' makes cross-thread reads safe (no stale cached values).
    // -----------------------------------------------------------------------
    public volatile float   remoteX, remoteY, remoteZ;
    public volatile float   remoteYaw, remotePitch;
    public volatile boolean connected = false;

    // -----------------------------------------------------------------------
    // Incoming event queues — network thread adds, game loop drains each frame.
    // ConcurrentLinkedQueue is thread-safe: one thread can add while
    // another thread calls poll() without any synchronization needed.
    // -----------------------------------------------------------------------
    private final Queue<int[]>  incomingBreaks = new ConcurrentLinkedQueue<>();
    private final Queue<int[]>  incomingPlaces = new ConcurrentLinkedQueue<>();
    private final Queue<String> incomingChats  = new ConcurrentLinkedQueue<>();

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------
    private final boolean isHost;
    private final String  hostIp;    // only used when isHost = false
    private PrintWriter   out;
    private final Object  writeLock = new Object();  // guards 'out' for thread safety

    /**
     * @param isHost true if you are hosting, false if you are joining
     * @param hostIp the host's IP address — ignored when isHost is true
     */
    public NetworkSession(boolean isHost, String hostIp) {
        this.isHost = isHost;
        this.hostIp = hostIp;
    }

    // -----------------------------------------------------------------------
    // Startup
    // -----------------------------------------------------------------------

    /** Call once before the game loop. Returns immediately; network runs in background. */
    public void start() {
        Thread t = new Thread(this::runNetworkLoop, "network-thread");
        t.setDaemon(true);   // don't keep JVM alive after game window closes
        t.start();

        // Start a background thread that reads chat messages from the console.
        // You type in IntelliJ's console → it sends to the other player.
        startChatInputThread();
    }

    private void runNetworkLoop() {
        try {
            Socket socket = isHost ? waitForConnection() : connectToHost();
            if (socket == null) return;

            // Set up text streams
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            synchronized (writeLock) {
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
                // 'true' = auto-flush: data is sent immediately on println(), not buffered
            }

            connected = true;
            System.out.println("[Net] Connected! Type messages in this console to chat.");

            // Main network loop: read incoming messages forever
            String line;
            while ((line = in.readLine()) != null) {
                handleIncoming(line);
            }

        } catch (IOException e) {
            System.err.println("[Net] Connection error: " + e.getMessage());
        }

        connected = false;
        System.out.println("[Net] Disconnected.");
    }

    private Socket waitForConnection() throws IOException {
        System.out.println("[Net] Hosting on port " + PORT + " — waiting for friend...");
        System.out.println("[Net] Your friend should run: new NetworkSession(false, \"YOUR_IP\")");
        ServerSocket serverSocket = new ServerSocket(PORT, 1);
        Socket client = serverSocket.accept();
        System.out.println("[Net] Friend connected from " + client.getInetAddress());
        return client;
    }

    private Socket connectToHost() {
        System.out.println("[Net] Connecting to " + hostIp + ":" + PORT + " ...");
        try {
            Socket socket = new Socket(hostIp, PORT);
            System.out.println("[Net] Connected to host!");
            return socket;
        } catch (IOException e) {
            System.err.println("[Net] Could not connect: " + e.getMessage());
            System.err.println("[Net] Is the host running? Is the IP \"" + hostIp + "\" correct?");
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Incoming message parsing
    // -----------------------------------------------------------------------

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

        } else if (line.startsWith("BREAK:")) {
            String[] p = line.substring(6).split(",");
            if (p.length < 3) return;
            try {
                incomingBreaks.add(new int[]{
                        Integer.parseInt(p[0]),
                        Integer.parseInt(p[1]),
                        Integer.parseInt(p[2])
                });
            } catch (NumberFormatException ignored) {}

        } else if (line.startsWith("PLACE:")) {
            String[] p = line.substring(6).split(",");
            if (p.length < 4) return;
            try {
                incomingPlaces.add(new int[]{
                        Integer.parseInt(p[0]),
                        Integer.parseInt(p[1]),
                        Integer.parseInt(p[2]),
                        Integer.parseInt(p[3])   // Block.ordinal() — index in Block.values()
                });
            } catch (NumberFormatException ignored) {}

        } else if (line.startsWith("CHAT:")) {
            String msg = line.substring(5);
            incomingChats.add(msg);
            System.out.println("[Friend] " + msg);  // print immediately so you see it
        }
    }

    // -----------------------------------------------------------------------
    // Outgoing — called from the game loop
    // -----------------------------------------------------------------------

    /** Send your position. Call every frame. */
    public void sendPosition(float x, float y, float z, float yaw, float pitch) {
        send("POS:" + x + "," + y + "," + z + "," + yaw + "," + pitch);
    }

    /** Tell the other player you broke a block. */
    public void sendBreak(int x, int y, int z) {
        send("BREAK:" + x + "," + y + "," + z);
    }

    /** Tell the other player you placed a block. */
    public void sendPlace(int x, int y, int z, Block block) {
        send("PLACE:" + x + "," + y + "," + z + "," + block.ordinal());
    }

    /** Send a chat message. */
    public void sendChat(String message) {
        send("CHAT:" + message);
    }

    private void send(String message) {
        synchronized (writeLock) {
            if (out != null) out.println(message);
        }
    }

    // -----------------------------------------------------------------------
    // Event polling — call these from the game loop each frame
    // -----------------------------------------------------------------------

    /** Returns the [x,y,z] of a block the other player broke, or null. */
    public int[] pollBreak() { return incomingBreaks.poll(); }

    /** Returns [x,y,z,blockOrdinal] of a block the other player placed, or null. */
    public int[] pollPlace() { return incomingPlaces.poll(); }

    /** Returns a chat message the other player sent, or null. */
    public String pollChat() { return incomingChats.poll(); }

    // -----------------------------------------------------------------------
    // Chat input — reads from IntelliJ's console on a background thread
    // -----------------------------------------------------------------------

    private void startChatInputThread() {
        Thread chatThread = new Thread(() -> {
            BufferedReader consoleIn = new BufferedReader(new InputStreamReader(System.in));
            try {
                String line;
                while ((line = consoleIn.readLine()) != null) {
                    if (!line.isBlank()) {
                        System.out.println("[You] " + line);
                        sendChat(line);
                    }
                }
            } catch (IOException ignored) {}
        }, "chat-input-thread");
        chatThread.setDaemon(true);
        chatThread.start();
    }
}