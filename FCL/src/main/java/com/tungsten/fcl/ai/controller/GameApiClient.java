package com.tungsten.fcl.ai.controller;

import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 游戏 API 客户端 —— AI Bridge Mod HTTP API 封装
 *
 * 由原 AI-Minecraft-Launcher 的 GameApiClient 移植并重写：
 * 去除 OkHttp/Retrofit 依赖，改用 HttpURLConnection + Gson，
 * 使其无需修改 FCL 构建依赖即可工作。
 */
public class GameApiClient {

    private final Gson gson = new Gson();
    private final int port;
    private final String baseUrl;

    public GameApiClient(int port) {
        this.port = port;
        this.baseUrl = "http://127.0.0.1:" + port;
    }

    public int getPort() {
        return port;
    }

    // ==================== 数据类（与 Bridge API 一致） ====================

    public static class GameStateResponse {
        public boolean connected;
        public List<Double> position;
        public float health;
        public int food;
        public float saturation;
        public int xpLevel;
        public float xpProgress;
        public List<Double> rotation;
        public String dimension;
        public long worldTime;
        public boolean isDay;
        public boolean sprinting;
        public boolean sneaking;
        public boolean onGround;
        public boolean inWater;
        public boolean hasSkyLight;
        public int serverPort;
    }

    public static class InventoryResponse {
        public boolean connected;
        public List<ItemInfo> mainInventory;
        public List<ItemInfo> armor;
        public int selectedSlot;
        public ItemInfo offhand;
    }

    public static class ItemInfo {
        public String name;
        public int count;
        public int slot;
    }

    public static class BlocksResponse {
        public boolean connected;
        public List<BlockInfo> nearbyBlocks;
        public Map<String, Integer> blockSummary;
        public List<EntityInfo> nearbyEntities;
        public int scanRadius;
    }

    public static class BlockInfo {
        public String name;
        public int dx;
        public int dy;
        public int dz;
    }

    public static class EntityInfo {
        public String type;
        public double dx;
        public double dy;
        public double dz;
        public double distance;
    }

    public static class ActionResult {
        public boolean success;
        public String message;
    }

    public static class ChatRequest {
        public String message;

        public ChatRequest(String message) {
            this.message = message;
        }
    }

    public static class ActionRequest {
        public String action;
        public Map<String, Object> params;

        public ActionRequest(String action, Map<String, Object> params) {
            this.action = action;
            this.params = params != null ? params : new HashMap<>();
        }
    }

    // ==================== API 方法 ====================

    public GameStateResponse getGameState() throws Exception {
        return getJson("/api/state", GameStateResponse.class);
    }

    public InventoryResponse getInventory() throws Exception {
        return getJson("/api/inventory", InventoryResponse.class);
    }

    public BlocksResponse getNearbyBlocks(int radius) throws Exception {
        return getJson("/api/blocks?radius=" + radius, BlocksResponse.class);
    }

    public ActionResult executeAction(String action, Map<String, Object> params) throws Exception {
        ActionRequest req = new ActionRequest(action, params);
        String body = request("POST", "/api/action", gson.toJson(req));
        return gson.fromJson(body, ActionResult.class);
    }

    public ActionResult sendChat(String message) throws Exception {
        ChatRequest req = new ChatRequest(message);
        String body = request("POST", "/api/chat", gson.toJson(req));
        return gson.fromJson(body, ActionResult.class);
    }

    public byte[] getScreenshot() throws Exception {
        return requestBytes("/api/screenshot");
    }

    /**
     * 健康检查：Bridge 服务是否可用
     */
    public boolean isConnected() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + "/api/health").openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            return code == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ==================== HTTP 工具 ====================

    private <T> T getJson(String path, Class<T> clazz) throws Exception {
        String body = request("GET", path, null);
        return gson.fromJson(body, clazz);
    }

    private String request(String method, String path, String jsonBody) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/json");
            if ("POST".equals(method)) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes("UTF-8"));
                    os.flush();
                }
            }
            try (InputStream is = conn.getInputStream()) {
                return readAll(is);
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private byte[] requestBytes(String path) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), "UTF-8");
    }
}
