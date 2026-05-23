package com.leaf.game;

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

    private RaycastResult lastTarget = null;

    // MULTIPLAYER
    private NetworkSession network;
    private RemotePlayer   remotePlayer;

    // BLOCK INTERACTION
    private final boolean[] leftClickThisFrame  = {false};
    private final boolean[] rightClickThisFrame = {false};

    private final double[]  lastMouseX = {640.0};
    private final double[]  lastMouseY = {360.0};
    private final boolean[] firstMouse = {true};

    private final ImGuiImplGlfw imguiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3  imguiGl3  = new ImGuiImplGl3();

    // UI STATES
    private boolean showDebug = false;
    private boolean showChat  = false;
    private final ImString chatInput = new ImString(256);
    private final List<String> chatHistory = new ArrayList<>();

    // INVENTORY & HOTBAR
    private final Inventory inventory = new Inventory();
    private final List<Block> activeHotbar = new ArrayList<>();
    private int selectedSlot = 0;
    private Block selectedBlock = Block.AIR;

    // DROPPED ENTITIES
    private final List<DroppedItem> droppedItems = new ArrayList<>();
    private final Map<Block, Mesh> itemMeshes = new HashMap<>();

    // BREAKING
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
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                if (showChat) {
                    showChat = false;
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                } else {
                    glfwSetWindowShouldClose(win, true);
                }
            }
            if (key == GLFW_KEY_F3 && action == GLFW_RELEASE && !showChat) {
                showDebug = !showDebug;
                glfwSetInputMode(window, GLFW_CURSOR, showDebug ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
            }
            // Toggle Chat with 'T'
            if (key == GLFW_KEY_T && action == GLFW_RELEASE && !showChat && !showDebug) {
                showChat = true;
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
            }

            // Hotbar Selection (Keys 1–9)
            if (action == GLFW_PRESS && !showChat && !showDebug) {
                if (key >= GLFW_KEY_1 && key <= GLFW_KEY_9) {
                    int slotIndex = key - GLFW_KEY_1;
                    if (slotIndex < activeHotbar.size()) {
                        selectedSlot = slotIndex;
                    }
                }
            }
        });

        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (showDebug || showChat) return;

            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                breakingActive = (action == GLFW_PRESS || action == GLFW_REPEAT);
                if (action == GLFW_RELEASE) {
                    breakProgress = 0.0f;
                }
            }

            if (button == GLFW_MOUSE_BUTTON_RIGHT && action == GLFW_PRESS) {
                if (lastTarget != null && lastTarget.hit && selectedBlock != Block.AIR) {
                    if (!playerOccupies(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ)) {
                        if (inventory.useBlock(selectedBlock)) {
                            world.setBlock(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ, selectedBlock);
                            markNeighborChunksDirty(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ);
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
            if (showDebug || showChat) return;

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

        Shader shader = new Shader(
                "src/main/resources/shaders/vertex.glsl",
                "src/main/resources/shaders/fragment.glsl"
        );

        Camera camera = new Camera();
        setupMouseLook(camera);

        this.player   = new Player(16.0f, 60.0f, 16.0f);
        this.world    = new World();
        this.worldGen = new WorldGen();

        network = new NetworkSession(true, null);
        network.start();
        remotePlayer = new RemotePlayer();

        world.updateChunks(world, worldGen, player);
        Matrix4f model = new Matrix4f();
        double lastTime = glfwGetTime();

        while (!glfwWindowShouldClose(window)) {
            double now       = glfwGetTime();
            float  deltaTime = (float)(now - lastTime);
            lastTime = now;

            // Only update game systems if chat or debug isn't open
            if (!showChat && !showDebug) {
                player.update(window, camera, world, deltaTime);
                updateBreaking(deltaTime);
            } else {
                breakingActive = false;
            }

            // ── MULTIPLAYER SYNC ──
            if (network != null && network.connected) {
                if (network.seedReceived) {
                    GameConfig.seed = network.newSeed;
                    regenerateWorld();
                    network.seedReceived = false;
                }

                network.sendPosition(player.position.x, player.position.y, player.position.z, camera.yaw, camera.pitch);

                remotePlayer.targetX = network.remoteX;
                remotePlayer.targetY = network.remoteY;
                remotePlayer.targetZ = network.remoteZ;
                remotePlayer.update(deltaTime);

                // 3. Process blocks friend broke
                int[] brk = network.pollBreak();
                if (brk != null) {
                    // ── THE MULTIPLAYER FIX ──
                    // Find what block was there, and spawn the floating item on OUR screen too!
                    Block brokenBlock = world.getBlock(brk[0], brk[1], brk[2]);
                    if (brokenBlock.isSolid()) {
                        droppedItems.add(new DroppedItem(brk[0], brk[1], brk[2], brokenBlock));
                    }

                    world.setBlock(brk[0], brk[1], brk[2], Block.AIR);
                    markNeighborChunksDirty(brk[0], brk[1], brk[2]);
                }

                int[] plc = network.pollPlace();
                if (plc != null) {
                    Block placedBlock = Block.values()[plc[3]];
                    world.setBlock(plc[0], plc[1], plc[2], placedBlock);
                    markNeighborChunksDirty(plc[0], plc[1], plc[2]);
                }

                String chat = network.pollChat();
                if (chat != null) {
                    chatHistory.add("[Friend]: " + chat);
                }

                //Process items friend picked up (delete from our screen)
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
            world.updateChunks(world, worldGen, player);

            // ── PROCESS FLOATING ENTITIES & MAGNET PICKUPS ──
            Vector3f chestPos = new Vector3f(player.position.x, player.position.y + 0.9f, player.position.z);
            for (int i = droppedItems.size() - 1; i >= 0; i--) {
                DroppedItem item = droppedItems.get(i);
                item.update(deltaTime, player.position);

                // FIXED: Check distance to chest, not feet!
                float dist = chestPos.distance(item.position);
                if (dist < 0.5f) {
                    inventory.addBlock(item.blockType);
                    item.alive = false;

                    // Tell the network WE picked it up!
                    if (network != null && network.connected) {
                        network.sendPickup(item.originX, item.originY, item.originZ);
                    }

                    droppedItems.remove(i);
                }
            }

            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            shader.bind();
            shader.setUniform("sunDirection",    new org.joml.Vector3f(GameConfig.sunDirX, GameConfig.sunDirY, GameConfig.sunDirZ));
            shader.setUniform("sunStrength",     GameConfig.sunStrength);
            shader.setUniform("ambientStrength", GameConfig.ambientStrength);
            Matrix4f view       = camera.getViewMatrix();
            Matrix4f projection = camera.getProjectionMatrix();

            for (Chunk chunk : world.getAllChunks()) {
                if (chunk.dirty) {
                    if (chunk.mesh != null) chunk.mesh.cleanup();
                    chunk.mesh = world.buildChunkMesh(chunk);
                    chunk.dirty = false;
                }
                if (chunk.mesh != null) {
                    Matrix4f mvp = new Matrix4f(projection).mul(view).mul(model);
                    shader.setUniform("mvp", mvp);
                    chunk.mesh.render();
                }
            }

            // Render floating dropped block entities
            for (DroppedItem item : droppedItems) {
                Mesh itemMesh = getItemMesh(item.blockType);
                float bob = (float) Math.sin(item.age * 3.0f) * 0.05f; // Floating animation
                Matrix4f itemModel = new Matrix4f()
                        .translate(item.position.x, item.position.y + bob, item.position.z)
                        .rotateY(item.age * 1.5f); // Spin slowly

                Matrix4f mvp = new Matrix4f(projection).mul(view).mul(itemModel);
                shader.setUniform("mvp", mvp);
                itemMesh.render();
            }

            if (network != null && network.connected) {
                remotePlayer.render(shader, projection, view);
            }
            shader.unbind();

            // Fetch actual window dimensions
            int[] ww = new int[1], wh = new int[1];
            glfwGetWindowSize(window, ww, wh);

            imguiGlfw.newFrame();
            ImGui.newFrame();

            renderHUD(ww[0], wh[0]);
            renderTargetCracks(camera, ww[0], wh[0]);

            if (showDebug) renderDebugMenu();
            if (showChat || !chatHistory.isEmpty()) renderChatBox(wh[0]);

            ImGui.render();
            imguiGl3.renderDrawData(ImGui.getDrawData());

            glfwSwapBuffers(window);
            glfwPollEvents();
        }

        for (Chunk chunk : world.getAllChunks()) {
            if (chunk.mesh != null) chunk.mesh.cleanup();
        }
        for (Mesh m : itemMeshes.values()) {
            m.cleanup();
        }
        shader.cleanup();
        imguiGl3.dispose();
        imguiGlfw.dispose();
        ImGui.destroyContext();
        remotePlayer.cleanup();
    }

    private void renderChatBox(int screenHeight) {
        ImGui.setNextWindowPos(10, screenHeight - 280);
        ImGui.setNextWindowSize(400, 200);

        int flags = imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoMove;
        if (!showChat) flags |= imgui.flag.ImGuiWindowFlags.NoBackground;

        ImGui.begin("Chat", flags);

        // Display recent history
        for (int i = Math.max(0, chatHistory.size() - 10); i < chatHistory.size(); i++) {
            ImGui.text(chatHistory.get(i));
        }

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
        // ONLY draw cracks if we are looking at a block and actively breaking it!
        if (lastTarget == null || !lastTarget.hit || !breakingActive || breakProgress <= 0) return;

        Matrix4f viewProj = new Matrix4f(camera.getProjectionMatrix()).mul(camera.getViewMatrix());
        int bx = lastTarget.hitX;
        int by = lastTarget.hitY;
        int bz = lastTarget.hitZ;

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
            if (proj[i].w > 0) {
                proj[i].x /= proj[i].w;
                proj[i].y /= proj[i].w;
            }
        }

        // Crack settings — dusty grey, getting darker/thicker as break progress increases
        int finalColor = ImGui.colorConvertFloat4ToU32(0.1f, 0.1f, 0.1f, 0.5f + (breakProgress * 0.4f));
        float finalThickness = 2.0f + (breakProgress * 3.0f);

        var draw = ImGui.getBackgroundDrawList();

        java.util.function.BiConsumer<Integer, Integer> drawCrack = (i, j) -> {
            if (proj[i].w > 0 && proj[j].w > 0) {
                float x1 = (proj[i].x + 1) * 0.5f * w;
                float y1 = (1 - proj[i].y) * 0.5f * h;
                float x2 = (proj[j].x + 1) * 0.5f * w;
                float y2 = (1 - proj[j].y) * 0.5f * h;
                draw.addLine(x1, y1, x2, y2, finalColor, finalThickness);
            }
        };

        // Draw cracks crossing the faces of the block as it breaks
        if (breakProgress > 0.1f) {
            drawCrack.accept(0, 6); // Diagonal across top/bottom
            drawCrack.accept(3, 5);
        }
        if (breakProgress > 0.4f) {
            drawCrack.accept(1, 7); // Cross-hatch diagonals
            drawCrack.accept(2, 4);
        }
        if (breakProgress > 0.7f) {
            drawCrack.accept(0, 2); // Core intersections
            drawCrack.accept(5, 7);
        }
    }

    private void renderHUD(float screenW, float screenH) {
        var draw = ImGui.getForegroundDrawList();
        float cx = screenW / 2.0f, cy = screenH / 2.0f;

        // ── CROSSHAIR ──
        float size = 10.0f;
        int white = ImGui.colorConvertFloat4ToU32(1, 1, 1, 0.9f);
        int black = ImGui.colorConvertFloat4ToU32(0, 0, 0, 0.6f);
        draw.addLine(cx - size - 1, cy, cx + size + 1, cy, black, 3.0f);
        draw.addLine(cx, cy - size - 1, cx, cy + size + 1, black, 3.0f);
        draw.addLine(cx - size, cy, cx + size, cy, white, 1.5f);
        draw.addLine(cx, cy - size, cx, cy + size, white, 1.5f);

        // ── DYNAMIC HOTBAR ──
        // Only show items we currently have in our inventory!
        activeHotbar.clear();
        for (Block b : Block.values()) {
            if (b != Block.AIR && inventory.getCount(b) > 0) {
                activeHotbar.add(b);
            }
        }

        // Lock selection index to current items
        if (activeHotbar.isEmpty()) {
            selectedBlock = Block.AIR;
        } else {
            if (selectedSlot >= activeHotbar.size()) {
                selectedSlot = activeHotbar.size() - 1;
            }
            selectedBlock = activeHotbar.get(selectedSlot);
        }

        float slotSize = 40.0f;
        float spacing = 5.0f;
        int numSlots = Math.max(1, activeHotbar.size()); // Draw at least 1 slot even if empty
        float totalWidth = (numSlots * slotSize) + ((numSlots - 1) * spacing);
        float startX = cx - (totalWidth / 2.0f);
        float startY = screenH - slotSize - 10.0f;

        for (int i = 0; i < numSlots; i++) {
            float x = startX + i * (slotSize + spacing);
            float y = startY;

            // Highlight selected slot
            int bgCol = (!activeHotbar.isEmpty() && i == selectedSlot)
                    ? ImGui.colorConvertFloat4ToU32(0.6f, 0.6f, 0.6f, 0.8f)
                    : ImGui.colorConvertFloat4ToU32(0.2f, 0.2f, 0.2f, 0.8f);
            draw.addRectFilled(x, y, x + slotSize, y + slotSize, bgCol, 4.0f);

            int outCol = (!activeHotbar.isEmpty() && i == selectedSlot)
                    ? ImGui.colorConvertFloat4ToU32(1.0f, 1.0f, 1.0f, 1.0f)
                    : ImGui.colorConvertFloat4ToU32(0.0f, 0.0f, 0.0f, 0.8f);
            draw.addRect(x, y, x + slotSize, y + slotSize, outCol, 4.0f, 0, (!activeHotbar.isEmpty() && i == selectedSlot) ? 3.0f : 1.5f);

            // Draw block inside the slot
            if (i < activeHotbar.size()) {
                Block b = activeHotbar.get(i);
                float shrink = 8.0f;
                int blockCol = ImGui.colorConvertFloat4ToU32(b.r, b.g, b.b, 1.0f);
                draw.addRectFilled(x + shrink, y + shrink, x + slotSize - shrink, y + slotSize - shrink, blockCol, 2.0f);
                draw.addRect(x + shrink, y + shrink, x + slotSize - shrink, y + slotSize - shrink, black, 2.0f, 0, 1.5f);

                int count = inventory.getCount(b);
                String countStr = String.valueOf(count);
                draw.addText(x + slotSize - 14, y + slotSize - 18, black, countStr); // Shadow
                draw.addText(x + slotSize - 15, y + slotSize - 19, white, countStr); // Text
            }

            // Slot Hotkey
            draw.addText(x + 4, y + 4, ImGui.colorConvertFloat4ToU32(1,1,1,0.5f), String.valueOf(i + 1));
        }

        // Draw Selected Block Name
        if (selectedBlock != Block.AIR) {
            ImGui.setNextWindowPos(cx - 50, startY - 35);
            ImGui.setNextWindowSize(100, 25);
            ImGui.begin("##blockname", imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoBackground | imgui.flag.ImGuiWindowFlags.NoInputs);
            ImGui.text(selectedBlock.name());
            ImGui.end();
        }
    }

    private void updateBreaking(float deltaTime) {
        if (!breakingActive || lastTarget == null || !lastTarget.hit) {
            breakProgress = 0.0f;
            return;
        }

        int tx = lastTarget.hitX;
        int ty = lastTarget.hitY;
        int tz = lastTarget.hitZ;

        if (tx != breakX || ty != breakY || tz != breakZ) {
            breakProgress = 0.0f;
            breakX = tx;
            breakY = ty;
            breakZ = tz;
        }

        Block target = world.getBlock(tx, ty, tz);
        if (!target.isSolid()) {
            breakProgress = 0.0f;
            return;
        }

        breakProgress += deltaTime / target.hardness;

        if (breakProgress >= 1.0f) {
            // Spawn floating block entity!
            droppedItems.add(new DroppedItem(tx, ty, tz, target));

            world.setBlock(tx, ty, tz, Block.AIR);
            markNeighborChunksDirty(tx, ty, tz);
            if (network != null && network.connected) network.sendBreak(tx, ty, tz);
            breakProgress = 0.0f;
        }
    }

    private void renderDebugMenu() {
        ImGui.begin("Settings");
        float[] fov = { GameConfig.fov };
        if (ImGui.sliderFloat("FOV", fov, 30f, 120f)) GameConfig.fov = fov[0];
        int[] rd = { GameConfig.renderDistance };
        if (ImGui.sliderInt("Render Distance", rd, 2, 16)) GameConfig.renderDistance = rd[0];
        ImGui.end();
    }

    private void regenerateWorld() {
        for (Chunk chunk : world.getAllChunks()) {
            if (chunk.mesh != null) chunk.mesh.cleanup();
        }
        world.clearAllChunks();
        worldGen.resetSeed(GameConfig.seed);
        player.position.y = 60.0f;
    }

    private void markNeighborChunksDirty(int wx, int wy, int wz) {
        int[][] neighbors = { {wx+1, wy, wz}, {wx-1, wy, wz}, {wx, wy+1, wz}, {wx, wy-1, wz}, {wx, wy, wz+1}, {wx, wy, wz-1} };
        for (int[] n : neighbors) {
            int ncx = Math.floorDiv(n[0], Chunk.SIZE);
            int ncz = Math.floorDiv(n[2], Chunk.SIZE);
            Chunk neighbor = world.getChunk(ncx, ncz);
            if (neighbor != null) neighbor.dirty = true;
        }
    }

    private boolean playerOccupies(int bx, int by, int bz) {
        float px = player.position.x, py = player.position.y, pz = player.position.z;
        float halfW = 0.3f, height = 1.8f;
        return px + halfW > bx && px - halfW < bx + 1 && py + height > by && py < by + 1 && pz + halfW > bz && pz - halfW < bz + 1;
    }

    // Helper to generate and cache miniature floating meshes
    // Helper to generate and cache perfectly centered miniature floating meshes
    private Mesh getItemMesh(Block block) {
        return itemMeshes.computeIfAbsent(block, b -> {
            float w = 0.12f; // half-width on all axes (makes a perfect 0.24 cube)
            float[] topCol    = {b.r * 1.0f, b.g * 1.0f, b.b * 1.0f};
            float[] sideCol   = {b.r * 0.75f, b.g * 0.75f, b.b * 0.75f};
            float[] bottomCol = {b.r * 0.5f, b.g * 0.5f, b.b * 0.5f};

            float[] v = {
                    // TOP (y = w)
                    -w,  w, -w,  topCol[0], topCol[1], topCol[2], 0, 1, 0,
                    w,  w, -w,  topCol[0], topCol[1], topCol[2], 0, 1, 0,
                    w,  w,  w,  topCol[0], topCol[1], topCol[2], 0, 1, 0,
                    -w,  w,  w,  topCol[0], topCol[1], topCol[2], 0, 1, 0,

                    // BOTTOM (y = -w)
                    -w, -w,  w,  bottomCol[0], bottomCol[1], bottomCol[2], 0, -1, 0,
                    w, -w,  w,  bottomCol[0], bottomCol[1], bottomCol[2], 0, -1, 0,
                    w, -w, -w,  bottomCol[0], bottomCol[1], bottomCol[2], 0, -1, 0,
                    -w, -w, -w,  bottomCol[0], bottomCol[1], bottomCol[2], 0, -1, 0,

                    // FRONT (+Z)
                    -w, -w,  w,  sideCol[0], sideCol[1], sideCol[2], 0, 0, 1,
                    w, -w,  w,  sideCol[0], sideCol[1], sideCol[2], 0, 0, 1,
                    w,  w,  w,  sideCol[0], sideCol[1], sideCol[2], 0, 0, 1,
                    -w,  w,  w,  sideCol[0], sideCol[1], sideCol[2], 0, 0, 1,

                    // BACK (-Z)
                    w, -w, -w,  sideCol[0], sideCol[1], sideCol[2], 0, 0, -1,
                    -w, -w, -w,  sideCol[0], sideCol[1], sideCol[2], 0, 0, -1,
                    -w,  w, -w,  sideCol[0], sideCol[1], sideCol[2], 0, 0, -1,
                    w,  w, -w,  sideCol[0], sideCol[1], sideCol[2], 0, 0, -1,

                    // RIGHT (+X)
                    w, -w,  w,  sideCol[0], sideCol[1], sideCol[2], 1, 0, 0,
                    w, -w, -w,  sideCol[0], sideCol[1], sideCol[2], 1, 0, 0,
                    w,  w, -w,  sideCol[0], sideCol[1], sideCol[2], 1, 0, 0,
                    w,  w,  w,  sideCol[0], sideCol[1], sideCol[2], 1, 0, 0,

                    // LEFT (-X)
                    -w, -w, -w,  sideCol[0], sideCol[1], sideCol[2], -1, 0, 0,
                    -w, -w,  w,  sideCol[0], sideCol[1], sideCol[2], -1, 0, 0,
                    -w,  w,  w,  sideCol[0], sideCol[1], sideCol[2], -1, 0, 0,
                    -w,  w, -w,  sideCol[0], sideCol[1], sideCol[2], -1, 0, 0,
            };

            int[] idx = {
                    0, 1, 2,  2, 3, 0,
                    4, 5, 6,  6, 7, 4,
                    8, 9, 10, 10, 11, 8,
                    12, 13, 14, 14, 15, 12,
                    16, 17, 18, 18, 19, 16,
                    20, 21, 22, 22, 23, 20
            };
            return new Mesh(v, idx);
        });
    }
}