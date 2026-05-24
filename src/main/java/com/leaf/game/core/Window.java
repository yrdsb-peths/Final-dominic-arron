// --- FILE: src/main/java/com/leaf/game/core/Window.java ---
package com.leaf.game.core;

import com.leaf.game.entity.DroppedItem;
import com.leaf.game.entity.Inventory;
import com.leaf.game.entity.Player;
import com.leaf.game.entity.RemotePlayer;
import com.leaf.game.net.NetworkSession;
import com.leaf.game.render.Mesh;
import com.leaf.game.render.Shader;
import com.leaf.game.util.Camera;
import com.leaf.game.util.NoiseVisualizer;
import com.leaf.game.util.RaycastResult;
import com.leaf.game.world.Block;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.World;
import com.leaf.game.world.WorldGen;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;

import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;

import imgui.ImGui;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class Window {
    private long window;
    private Player player;
    private World world;
    private WorldGen worldGen;

    private NoiseVisualizer noiseVis;
    private boolean showNoiseViewer = false;
    private RaycastResult lastTarget = null;

    private NetworkSession network;
    private RemotePlayer remotePlayer;

    private boolean networkInitialized = false;
    private final ImString ipInput = new ImString("127.0.0.1", 64);

    private final double[]  lastMouseX = {640.0};
    private final double[]  lastMouseY = {360.0};
    private final boolean[] firstMouse = {true};

    private final ImGuiImplGlfw imguiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3  imguiGl3  = new ImGuiImplGl3();

    // UI STATES
    private boolean isPaused  = false;
    private boolean showDebug = false;
    private boolean showChat  = false;
    private final ImString chatInput = new ImString(256);
    private final List<String> chatHistory = new ArrayList<>();

    private final Inventory inventory = new Inventory();
    private int selectedSlot = 0;
    private Block selectedBlock = Block.AIR;

    // Fixed 9 Slots
    private final Block[] hotbar = {
            Block.GRASS, Block.DIRT, Block.STONE, Block.WATER, // Added Water!
            Block.AIR, Block.AIR, Block.AIR, Block.AIR, Block.AIR
    };

    private final List<DroppedItem> droppedItems = new ArrayList<>();
    private final Map<Block, Mesh> itemMeshes = new HashMap<>();

    private float  breakProgress = 0.0f;
    private int    breakX, breakY, breakZ;
    private boolean breakingActive = false;

    public void run() {
        init();
        loop();
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }

    private void init() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE,               GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE,             GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,        GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        window = glfwCreateWindow(1280, 720, "Minecraft Clone", NULL, NULL);
        if (window == NULL) throw new RuntimeException("Failed to create window");

        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (!networkInitialized) return;

            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                if (showChat) {
                    showChat = false;
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                } else if (showNoiseViewer) {
                    showNoiseViewer = false;
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                } else {
                    // Toggle Pause Menu
                    isPaused = !isPaused;
                    glfwSetInputMode(window, GLFW_CURSOR, isPaused ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
                }
            }

            if (isPaused) return; // Block game inputs while paused

            if (key == GLFW_KEY_F3 && action == GLFW_RELEASE && !showChat) {
                showDebug = !showDebug;
                glfwSetInputMode(window, GLFW_CURSOR, (showDebug || showNoiseViewer) ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
            }

            if (key == GLFW_KEY_F4 && action == GLFW_RELEASE && !showChat) {
                showNoiseViewer = !showNoiseViewer;
                glfwSetInputMode(window, GLFW_CURSOR, (showDebug || showNoiseViewer) ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
            }

            if (key == GLFW_KEY_T && action == GLFW_RELEASE && !showChat && !showDebug && !isPaused) {
                showChat = true;
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
            }

            if (action == GLFW_PRESS && !showChat && !showDebug) {
                if (key >= GLFW_KEY_1 && key <= GLFW_KEY_9) {
                    selectedSlot = key - GLFW_KEY_1;
                }
            }
        });

        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (!networkInitialized || showDebug || showChat || showNoiseViewer || isPaused) return;

            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                breakingActive = (action == GLFW_PRESS || action == GLFW_REPEAT);
                if (action == GLFW_RELEASE) breakProgress = 0.0f;
            }

            if (button == GLFW_MOUSE_BUTTON_RIGHT && action == GLFW_PRESS) {
                if (lastTarget != null && lastTarget.hit && selectedBlock != Block.AIR) {
                    if (!playerOccupies(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ) &&
                            !remotePlayerOccupies(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ)) {

                        if (inventory.useBlock(selectedBlock)) {
                            world.setBlock(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ, selectedBlock);
                            if (network != null && network.connected) {
                                network.sendPlace(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ, selectedBlock);
                            }
                        }
                    }
                }
            }
        });
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);

        ImGui.createContext();
        imguiGlfw.init(window, true);
    }

    private void setupMouseLook(Camera camera) {
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);

        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            if (!networkInitialized || showDebug || showChat || showNoiseViewer || isPaused) return;

            if (firstMouse[0]) {
                lastMouseX[0] = xpos;
                lastMouseY[0] = ypos;
                firstMouse[0] = false;
                return;
            }

            float dx = (float)(xpos - lastMouseX[0]);
            float dy = (float)(ypos - lastMouseY[0]);
            lastMouseX[0] = xpos;
            lastMouseY[0] = ypos;

            camera.yaw   += dx * GameConfig.mouseSensitivity;
            camera.pitch -= dy * GameConfig.mouseSensitivity;
            camera.clampPitch();
        });
    }

    private void loop() {
        GL.createCapabilities();
        imguiGl3.init("#version 330");

        glEnable(GL_DEPTH_TEST);
        glClearColor(0.5f, 0.7f, 0.9f, 1.0f);

        Shader shader = new Shader("src/main/resources/shaders/vertex.glsl", "src/main/resources/shaders/fragment.glsl");
        Camera camera = new Camera();
        setupMouseLook(camera);

        this.player   = new Player(16.0f, 100.0f, 16.0f);
        this.world    = new World();
        this.worldGen = new WorldGen();
        this.noiseVis = new NoiseVisualizer(worldGen);

        Matrix4f model = new Matrix4f();
        double lastTime = glfwGetTime();

        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);

        while (!glfwWindowShouldClose(window)) {
            double now       = glfwGetTime();
            float  deltaTime = (float)(now - lastTime);
            lastTime = now;

            int[] ww = new int[1], wh = new int[1];
            glfwGetWindowSize(window, ww, wh);

            if (networkInitialized) {
                if (!showChat && !showDebug && !showNoiseViewer && !isPaused) {
                    player.update(window, camera, world, deltaTime);
                    updateBreaking(deltaTime);
                } else {
                    breakingActive = false;
                }

                if (network != null && network.connected) {
                    if (network.seedReceived) {
                        GameConfig.seed = network.newSeed;
                        world.clearAllChunks();
                        worldGen.resetSeed(GameConfig.seed);
                        player.position.y = 100.0f;
                        network.seedReceived = false;
                    }

                    network.sendPosition(player.position.x, player.position.y, player.position.z, camera.yaw, camera.pitch);

                    remotePlayer.targetX = network.remoteX;
                    remotePlayer.targetY = network.remoteY;
                    remotePlayer.targetZ = network.remoteZ;
                    remotePlayer.targetYaw = network.remoteYaw;
                    remotePlayer.update(deltaTime);

                    int[] brk = network.pollBreak();
                    if (brk != null) {
                        Block brokenBlock = world.getBlock(brk[0], brk[1], brk[2]);
                        if (brokenBlock.isSolid()) droppedItems.add(new DroppedItem(brk[0], brk[1], brk[2], brokenBlock));
                        world.setBlock(brk[0], brk[1], brk[2], Block.AIR);
                    }

                    int[] plc = network.pollPlace();
                    if (plc != null) {
                        world.setBlock(plc[0], plc[1], plc[2], Block.values()[plc[3]]);
                    }

                    String chat = network.pollChat();
                    if (chat != null) chatHistory.add("[Friend]: " + chat);

                    int[] pk = network.pollPickup();
                    if (pk != null) {
                        for (int i = 0; i < droppedItems.size(); i++) {
                            DroppedItem item = droppedItems.get(i);
                            if (item.originX == pk[0] && item.originY == pk[1] && item.originZ == pk[2]) {
                                droppedItems.remove(i);
                                break;
                            }
                        }
                    }
                }

                lastTarget = player.getTargetBlock(camera, world);
                world.tickLiquids(deltaTime);
                world.updateChunks(world, worldGen, player);

                Vector3f chestPos = new Vector3f(player.position.x, player.position.y + 0.9f, player.position.z);
                for (int i = droppedItems.size() - 1; i >= 0; i--) {
                    DroppedItem item = droppedItems.get(i);
                    item.update(deltaTime, player.position);

                    if (chestPos.distance(item.position) < 0.5f) {
                        inventory.addBlock(item.blockType);
                        item.alive = false;
                        if (network != null && network.connected) network.sendPickup(item.originX, item.originY, item.originZ);
                        droppedItems.remove(i);
                    }
                }
            }

            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            if (networkInitialized) {
                shader.bind();
                shader.setUniform("sunDirection",    new org.joml.Vector3f(GameConfig.sunDirX, GameConfig.sunDirY, GameConfig.sunDirZ));
                shader.setUniform("sunStrength",     GameConfig.sunStrength);
                shader.setUniform("ambientStrength", GameConfig.ambientStrength);
                // Set the underwater uniform
                boolean isCameraUnderwater = world.getBlock(
                        (int)Math.floor(camera.position.x),
                        (int)Math.floor(camera.position.y),
                        (int)Math.floor(camera.position.z)).isLiquid();
                shader.setUniform("isUnderwater", isCameraUnderwater ? 1 : 0);

                Matrix4f view       = camera.getViewMatrix();
                Matrix4f projection = camera.getProjectionMatrix();

                // ── PASS 1: OPAQUE (Stone, Dirt, Grass, Sand) ──
                for (Chunk chunk : world.getAllChunks()) {
                    if (chunk.dirty) {
                        world.buildChunkMeshes(chunk); // Builds both opaque and transparent meshes
                    }
                    if (chunk.opaqueMesh != null) {
                        Matrix4f mvp = new Matrix4f(projection).mul(view).mul(model);
                        shader.setUniform("mvp", mvp);
                        chunk.opaqueMesh.render();
                    }
                }

                // ── PASS 2: TRANSPARENT (Water, Ice, Leaves) ──
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

                for (Chunk chunk : world.getAllChunks()) {
                    if (chunk.transparentMesh != null) {
                        Matrix4f mvp = new Matrix4f(projection).mul(view).mul(model);
                        shader.setUniform("mvp", mvp);
                        chunk.transparentMesh.render();
                    }
                }

                glDisable(GL_BLEND); // Turn blending off so ImGui and other things draw normally

                // ── RENDER DROPPED ITEMS ──
                for (DroppedItem item : droppedItems) {
                    Mesh itemMesh = getItemMesh(item.blockType);
                    float bob = (float) Math.sin(item.age * 3.0f) * 0.05f;
                    Matrix4f itemModel = new Matrix4f()
                            .translate(item.position.x, item.position.y + bob, item.position.z)
                            .rotateY(item.age * 1.5f);
                    Matrix4f mvp = new Matrix4f(projection).mul(view).mul(itemModel);
                    shader.setUniform("mvp", mvp);
                    itemMesh.render();
                }

                if (network != null && network.connected) {
                    remotePlayer.render(shader, projection, view);
                }
                shader.unbind();
            }

            imguiGlfw.newFrame();
            ImGui.newFrame();

            if (!networkInitialized) {
                renderConnectionMenu(ww[0], wh[0]);
            } else {
                renderHUD(ww[0], wh[0]);
                renderTargetCracks(camera, ww[0], wh[0]);
                if (showDebug) renderDebugMenu();
                if (showNoiseViewer) noiseVis.renderWindow(player);
                if (showChat || !chatHistory.isEmpty()) renderChatBox(wh[0]);
                if (isPaused) renderPauseMenu(ww[0], wh[0]);
            }

            ImGui.render();
            imguiGl3.renderDrawData(ImGui.getDrawData());

            glfwSwapBuffers(window);
            glfwPollEvents();

        }for (Chunk chunk : world.getAllChunks()) {
            if (chunk.opaqueMesh != null) chunk.opaqueMesh.cleanup();
            if (chunk.transparentMesh != null) chunk.transparentMesh.cleanup();
        }
        for (Mesh m : itemMeshes.values()) { m.cleanup(); }
        shader.cleanup();
        imguiGl3.dispose();
        noiseVis.cleanup();
        imguiGlfw.dispose();
        ImGui.destroyContext();
        if (remotePlayer != null) remotePlayer.cleanup();
    }

    private void renderConnectionMenu(float w, float h) {
        ImGui.setNextWindowPos(w / 2.0f - 150.0f, h / 2.0f - 140.0f);
        ImGui.setNextWindowSize(300.0f, 280.0f);
        ImGui.begin("Start Screen", imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoMove);

        ImGui.text("Minecraft Voxel Engine");
        ImGui.separator();
        ImGui.spacing();

        if (ImGui.button("Single Player", 280, 30)) {
            network = null;
            networkInitialized = true;
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        }

        ImGui.spacing();
        if (SaveManager.saveExists()) {
            if (ImGui.button("Load Saved Game", 280, 30)) {
                SaveManager.loadGame(world, player, inventory);
                worldGen.resetSeed(GameConfig.seed);
                world.clearAllChunks();
                network = null;
                networkInitialized = true;
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            }
            ImGui.spacing();
        }

        ImGui.separator();
        ImGui.spacing();

        if (ImGui.button("Host Multiplayer Game", 280, 30)) {
            network = new NetworkSession(true, null);
            network.start();
            remotePlayer = new RemotePlayer();
            networkInitialized = true;
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        }

        ImGui.spacing();
        ImGui.inputText("Host IP", ipInput);
        if (ImGui.button("Join Multiplayer Game", 280, 30)) {
            network = new NetworkSession(false, ipInput.get().trim());
            network.start();
            remotePlayer = new RemotePlayer();
            networkInitialized = true;
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        }

        ImGui.end();
    }

    private void renderPauseMenu(float w, float h) {
        ImGui.setNextWindowPos(w / 2.0f - 100.0f, h / 2.0f - 80.0f);
        ImGui.setNextWindowSize(200.0f, 160.0f);
        ImGui.begin("Paused", imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoMove);

        ImGui.text("Game Paused");
        ImGui.separator();
        ImGui.spacing();

        if (ImGui.button("Resume", 180, 30)) {
            isPaused = false;
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        }
        ImGui.spacing();
        if (ImGui.button("Save Game", 180, 30)) {
            SaveManager.saveGame(world, player, inventory);
        }
        ImGui.spacing();
        if (ImGui.button("Save & Quit", 180, 30)) {
            SaveManager.saveGame(world, player, inventory);
            glfwSetWindowShouldClose(window, true);
        }
        ImGui.end();
    }

    private void renderChatBox(int screenHeight) {
        ImGui.setNextWindowPos(10, screenHeight - 280);
        ImGui.setNextWindowSize(400, 200);

        int flags = imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoMove;
        if (!showChat) flags |= imgui.flag.ImGuiWindowFlags.NoBackground;

        ImGui.begin("Chat", flags);
        for (int i = Math.max(0, chatHistory.size() - 10); i < chatHistory.size(); i++) ImGui.text(chatHistory.get(i));

        if (showChat) {
            ImGui.setKeyboardFocusHere();
            if (ImGui.inputText("##chat", chatInput, imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue)) {
                String msg = chatInput.get().trim();
                if (!msg.isEmpty()) {
                    chatHistory.add("[You]: " + msg);
                    if (network != null && network.connected) network.sendChat(msg);
                    chatInput.set("");
                }
                showChat = false;
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            }
        }
        ImGui.end();
    }

    private void renderTargetCracks(Camera camera, float w, float h) {
        if (lastTarget == null || !lastTarget.hit || !breakingActive || breakProgress <= 0) return;
        Matrix4f viewProj = new Matrix4f(camera.getProjectionMatrix()).mul(camera.getViewMatrix());
        int bx = lastTarget.hitX, by = lastTarget.hitY, bz = lastTarget.hitZ;
        float e = 0.005f;
        Vector3f[] corners = {
                new Vector3f(bx-e, by-e, bz-e), new Vector3f(bx+1+e, by-e, bz-e),
                new Vector3f(bx+1+e, by+1+e, bz-e), new Vector3f(bx-e, by+1+e, bz-e),
                new Vector3f(bx-e, by-e, bz+1+e), new Vector3f(bx+1+e, by-e, bz+1+e),
                new Vector3f(bx+1+e, by+1+e, bz+1+e), new Vector3f(bx-e, by+1+e, bz+1+e)
        };

        org.joml.Vector4f[] proj = new org.joml.Vector4f[8];
        for (int i = 0; i < 8; i++) {
            proj[i] = new org.joml.Vector4f(corners[i].x, corners[i].y, corners[i].z, 1.0f).mul(viewProj);
            if (proj[i].w > 0) { proj[i].x /= proj[i].w; proj[i].y /= proj[i].w; }
        }

        int finalColor = ImGui.colorConvertFloat4ToU32(0.1f, 0.1f, 0.1f, 0.5f + (breakProgress * 0.4f));
        float finalThickness = 2.0f + (breakProgress * 3.0f);
        var draw = ImGui.getBackgroundDrawList();

        java.util.function.BiConsumer<Integer, Integer> drawCrack = (i, j) -> {
            if (proj[i].w > 0 && proj[j].w > 0) {
                float x1 = (proj[i].x + 1) * 0.5f * w, y1 = (1 - proj[i].y) * 0.5f * h;
                float x2 = (proj[j].x + 1) * 0.5f * w, y2 = (1 - proj[j].y) * 0.5f * h;
                draw.addLine(x1, y1, x2, y2, finalColor, finalThickness);
            }
        };

        if (breakProgress > 0.1f) { drawCrack.accept(0, 6); drawCrack.accept(3, 5); }
        if (breakProgress > 0.4f) { drawCrack.accept(1, 7); drawCrack.accept(2, 4); }
        if (breakProgress > 0.7f) { drawCrack.accept(0, 2); drawCrack.accept(5, 7); }
    }

    private void renderHUD(float screenW, float screenH) {
        var draw = ImGui.getForegroundDrawList();
        float cx = screenW / 2.0f, cy = screenH / 2.0f;

        // ── CROSSHAIR ──
        int white = ImGui.colorConvertFloat4ToU32(1, 1, 1, 0.9f);
        int black = ImGui.colorConvertFloat4ToU32(0, 0, 0, 0.6f);
        draw.addLine(cx - 11, cy, cx + 11, cy, black, 3.0f); draw.addLine(cx, cy - 11, cx, cy + 11, black, 3.0f);
        draw.addLine(cx - 10, cy, cx + 10, cy, white, 1.5f); draw.addLine(cx, cy - 10, cx, cy + 10, white, 1.5f);

        // ── HEALTH BAR ──
        float hpWidth = 200f;
        float hpHeight = 15f;
        float hpX = cx - (hpWidth / 2.0f);
        float hpY = screenH - 75f;
        draw.addRectFilled(hpX, hpY, hpX + hpWidth, hpY + hpHeight, ImGui.colorConvertFloat4ToU32(0.2f, 0, 0, 0.8f));
        float fillW = hpWidth * (Math.max(0, player.health) / player.maxHealth);
        draw.addRectFilled(hpX, hpY, hpX + fillW, hpY + hpHeight, ImGui.colorConvertFloat4ToU32(0.8f, 0.1f, 0.1f, 1.0f));
        draw.addRect(hpX, hpY, hpX + hpWidth, hpY + hpHeight, black, 0f, 0, 2.0f);

        // ── FIXED 9-SLOT HOTBAR ──
        float slotSize = 40.0f;
        float spacing = 5.0f;
        int numSlots = 9;
        float startX = cx - (((numSlots * slotSize) + ((numSlots - 1) * spacing)) / 2.0f);
        float startY = screenH - slotSize - 10.0f;

        selectedBlock = hotbar[selectedSlot];

        for (int i = 0; i < numSlots; i++) {
            float x = startX + i * (slotSize + spacing);

            int bgCol = (i == selectedSlot) ? ImGui.colorConvertFloat4ToU32(0.6f, 0.6f, 0.6f, 0.8f) : ImGui.colorConvertFloat4ToU32(0.2f, 0.2f, 0.2f, 0.8f);
            draw.addRectFilled(x, startY, x + slotSize, startY + slotSize, bgCol, 4.0f);

            int outCol = (i == selectedSlot) ? ImGui.colorConvertFloat4ToU32(1.0f, 1.0f, 1.0f, 1.0f) : ImGui.colorConvertFloat4ToU32(0.0f, 0.0f, 0.0f, 0.8f);
            draw.addRect(x, startY, x + slotSize, startY + slotSize, outCol, 4.0f, 0, (i == selectedSlot) ? 3.0f : 1.5f);

            Block b = hotbar[i];
            int count = inventory.getCount(b);

            // Only draw block graphic and count if we actually have some!
            if (b != Block.AIR && count > 0) {
                float shrink = 8.0f;
                int blockCol = ImGui.colorConvertFloat4ToU32(b.r, b.g, b.b, 1.0f);
                draw.addRectFilled(x + shrink, startY + shrink, x + slotSize - shrink, startY + slotSize - shrink, blockCol, 2.0f);
                draw.addRect(x + shrink, startY + shrink, x + slotSize - shrink, startY + slotSize - shrink, black, 2.0f, 0, 1.5f);

                String countStr = String.valueOf(count);
                draw.addText(x + slotSize - 14, startY + slotSize - 18, black, countStr);
                draw.addText(x + slotSize - 15, startY + slotSize - 19, white, countStr);
            }
        }
    }

    private void updateBreaking(float deltaTime) {
        if (!breakingActive || lastTarget == null || !lastTarget.hit) {
            breakProgress = 0.0f;
            return;
        }

        int tx = lastTarget.hitX, ty = lastTarget.hitY, tz = lastTarget.hitZ;
        if (tx != breakX || ty != breakY || tz != breakZ) {
            breakProgress = 0.0f; breakX = tx; breakY = ty; breakZ = tz;
        }

        Block target = world.getBlock(tx, ty, tz);
        if (!target.isSolid()) { breakProgress = 0.0f; return; }

        breakProgress += deltaTime / target.hardness;

        if (breakProgress >= 1.0f) {
            droppedItems.add(new DroppedItem(tx, ty, tz, target));
            world.setBlock(tx, ty, tz, Block.AIR);
            if (network != null && network.connected) network.sendBreak(tx, ty, tz);
            breakProgress = 0.0f;
        }
    }

    private void renderDebugMenu() {
        ImGui.begin("Debug");
        ImGui.text(String.format("XYZ: %.3f / %.3f / %.3f", player.position.x, player.position.y, player.position.z));
        ImGui.separator();
        float[] fov = { GameConfig.fov }; if (ImGui.sliderFloat("FOV", fov, 30f, 120f)) GameConfig.fov = fov[0];
        int[] rd = { GameConfig.renderDistance }; if (ImGui.sliderInt("Render Distance", rd, 2, 16)) GameConfig.renderDistance = rd[0];
        ImGui.end();
    }

    private void regenerateWorld() {
        for (Chunk chunk : world.getAllChunks()) {
        if (chunk.opaqueMesh != null) chunk.opaqueMesh.cleanup();
        if (chunk.transparentMesh != null) chunk.transparentMesh.cleanup();
        }
        world.clearAllChunks();
        worldGen.resetSeed(GameConfig.seed);
        player.position.y = 100.0f;
    }

    private void markNeighborChunksDirty(int wx, int wy, int wz) {
        int[][] neighbors = { {wx+1, wy, wz}, {wx-1, wy, wz}, {wx, wy+1, wz}, {wx, wy-1, wz}, {wx, wy, wz+1}, {wx, wy, wz-1} };
        for (int[] n : neighbors) {
            Chunk neighbor = world.getChunk(Math.floorDiv(n[0], Chunk.SIZE), Math.floorDiv(n[2], Chunk.SIZE));
            if (neighbor != null) neighbor.dirty = true;
        }
    }

    private boolean playerOccupies(int bx, int by, int bz) {
        float px = player.position.x, py = player.position.y, pz = player.position.z;
        return px + 0.3f > bx && px - 0.3f < bx + 1 && py + 1.8f > by && py < by + 1 && pz + 0.3f > bz && pz - 0.3f < bz + 1;
    }

    private boolean remotePlayerOccupies(int bx, int by, int bz) {
        if (network == null || !network.connected) return false;
        float px = remotePlayer.x, py = remotePlayer.y, pz = remotePlayer.z;
        return px + 0.3f > bx && px - 0.3f < bx + 1 && py + 1.8f > by && py < by + 1 && pz + 0.3f > bz && pz - 0.3f < bz + 1;
    }

    private Mesh getItemMesh(Block block) {
        return itemMeshes.computeIfAbsent(block, b -> {
            List<Float> verts = new ArrayList<>();
            List<Integer> idx = new ArrayList<>();
            int[] vIndex = {0};

            float w = 0.12f;
            float[] col = {b.r, b.g, b.b};
            addBox(verts, idx, vIndex, -w, -w, -w, w, w, w, col);

            float[] vArr = new float[verts.size()];
            for (int i = 0; i < verts.size(); i++) vArr[i] = verts.get(i);
            int[] iArr = new int[idx.size()];
            for (int i = 0; i < idx.size(); i++) iArr[i] = idx.get(i);

            return new Mesh(vArr, iArr);
        });
    }

    // Helper to generate 3D boxes for items cleanly with Alpha support!
    private void addBox(List<Float> verts, List<Integer> idx, int[] vIndex,
                        float minX, float minY, float minZ,
                        float maxX, float maxY, float maxZ, float[] col) {
        float[][] corners = {
                {minX, minY, maxZ}, {maxX, minY, maxZ}, {maxX, maxY, maxZ}, {minX, maxY, maxZ}, // Front
                {maxX, minY, minZ}, {minX, minY, minZ}, {minX, maxY, minZ}, {maxX, maxY, minZ}, // Back
                {minX, maxY, minZ}, {maxX, maxY, minZ}, {maxX, maxY, maxZ}, {minX, maxY, maxZ}, // Top
                {minX, minY, maxZ}, {maxX, minY, maxZ}, {maxX, minY, minZ}, {minX, minY, minZ}, // Bottom
                {maxX, minY, maxZ}, {maxX, minY, minZ}, {maxX, maxY, minZ}, {maxX, maxY, maxZ}, // Right
                {minX, minY, minZ}, {minX, minY, maxZ}, {minX, maxY, maxZ}, {minX, maxY, minZ}  // Left
        };

        for (int face = 0; face < 6; face++) {
            float shade = (face == 2) ? 1.0f : (face == 3 ? 0.5f : 0.8f);
            for (int i = 0; i < 4; i++) {
                float[] corner = corners[face * 4 + i];
                verts.add(corner[0]); verts.add(corner[1]); verts.add(corner[2]);
                verts.add(col[0]*shade); verts.add(col[1]*shade); verts.add(col[2]*shade);
                verts.add(1.0f); // <--- THE MISSING ALPHA CHANNEL THAT BROKE IT!
                verts.add(0f); verts.add(1f); verts.add(0f);
            }
            int b = vIndex[0];
            idx.add(b); idx.add(b+1); idx.add(b+2); idx.add(b+2); idx.add(b+3); idx.add(b);
            vIndex[0] += 4;
        }
    }
}
