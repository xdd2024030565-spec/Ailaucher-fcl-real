package com.tungsten.fcl.ai.controller;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 本地假人 HTTP 客户端 —— Bridge /api/fakeplayer/*
 *
 * 控制本地存档中的真·假人（打开/移除/动作/状态）。
 */
public class FakePlayerClient {

    private final Gson gson = new Gson();
    private final String baseUrl;

    public FakePlayerClient(int port) {
        this.baseUrl = "http://127.0.0.1:" + port;
    }

    /**
     * 生成假人
     */
    public boolean spawn(String name, double x, double y, double z) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("z", z);
        String resp = post("/api/fakeplayer/spawn", gson.toJson(body));
        return isSuccess(resp);
    }

    /**
     * 移除假人
     */
    public boolean remove(String name) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        String resp = post("/api/fakeplayer/remove", gson.toJson(body));
        return isSuccess(resp);
    }

    /**
     * 查询假人状态
     */
    public JsonObject state(String name) throws Exception {
        String resp = get("/api/fakeplayer/state?name=" + urlEncode(name));
        return JsonParser.parseString(resp).getAsJsonObject();
    }

    /**
     * 判断假人是否在线
     */
    public boolean isOnline(String name) {
        try {
            JsonObject state = state(name);
            return state.has("online") && state.get("online").getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 执行假人动作
     *
     * @param action 动作 JSON（会自动附加 name 字段）
     */
    public boolean action(String name, JsonObject action) throws Exception {
        if (action == null) {
            return false;
        }
        action.addProperty("name", name);
        String resp = post("/api/fakeplayer/action", gson.toJson(action));
        return isSuccess(resp);
    }

    /**
     * 假人发送聊天（以假人名义广播）
     */
    public boolean chat(String name, String message) throws Exception {
        JsonObject action = new JsonObject();
        action.addProperty("action", "chat");
        action.addProperty("message", message);
        return action(name, action);
    }

    // ==================== HTTP 工具 ====================

    private boolean isSuccess(String resp) {
        try {
            JsonObject json = JsonParser.parseString(resp).getAsJsonObject();
            return json.has("success") && json.get("success").getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private String post(String path, String jsonBody) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes("UTF-8"));
                os.flush();
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

    private String get(String path) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            try (InputStream is = conn.getInputStream()) {
                return readAll(is);
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

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
