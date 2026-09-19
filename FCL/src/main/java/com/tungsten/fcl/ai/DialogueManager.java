package com.tungsten.fcl.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tungsten.fcl.ai.controller.FakePlayerClient;
import com.tungsten.fcl.ai.controller.LlmClient;
import com.tungsten.fclcore.util.Logging;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Level;

/**
 * 对话管理器 —— AI 与玩家的聊天交流
 *
 * 轮询 /api/dialogue/poll 获取聊天消息：
 * - 他人消息中包含触发词或 AI 名字时，调用 LLM 生成回复
 * - 自己发送的触发词消息同样处理（单机下指令场景）
 *
 * 回复途径按模式选择：
 * - 自控/远程模式：以玩家身份发回聊天（/api/dialogue/reply）
 * - 本地假人模式：以假人名义广播（fakeplayer chat 动作）
 */
public class DialogueManager {

    /** 回复发送接口（按模式注入） */
    public interface ReplySink {
        boolean reply(String text);
    }

    private static final int MAX_REPLY_HISTORY = 5;

    private final Gson gson = new Gson();
    private final String baseUrl;
    private final LlmClient llm;
    private final String aiName;
    private final String trigger;
    private ReplySink replySink;

    private final Deque<String> recentReplies = new ArrayDeque<>();

    public DialogueManager(int port, LlmClient llm, String aiName, String trigger) {
        this.baseUrl = "http://127.0.0.1:" + port;
        this.llm = llm;
        this.aiName = aiName;
        this.trigger = trigger;
    }

    public void setReplySink(ReplySink sink) {
        this.replySink = sink;
    }

    /** 使用假人客户端作为回复途径（本地假人模式） */
    public void useFakePlayerReply(FakePlayerClient client, String name) {
        this.replySink = text -> {
            try {
                return client.chat(name, text);
            } catch (Exception e) {
                return false;
            }
        };
    }

    /** 使用玩家聊天作为回复途径 */
    public void usePlayerReply() {
        this.replySink = text -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("message", text);
                String resp = post("/api/dialogue/reply", gson.toJson(body));
                JsonObject json = JsonParser.parseString(resp).getAsJsonObject();
                return json.has("success") && json.get("success").getAsBoolean();
            } catch (Exception e) {
                return false;
            }
        };
    }

    /**
     * 单次对话轮询（由控制器循环调用）
     */
    public void tick() {
        try {
            String resp = get("/api/dialogue/poll");
            JsonObject json = JsonParser.parseString(resp).getAsJsonObject();

            handleMessages(json.getAsJsonArray("incoming"));
            handleMessages(json.getAsJsonArray("outgoing"));
        } catch (Exception e) {
            Logging.LOG.log(Level.FINE, "对话轮询失败", e);
        }
    }

    private void handleMessages(JsonArray messages) {
        if (messages == null) {
            return;
        }
        for (JsonElement elem : messages) {
            try {
                JsonObject msg = elem.getAsJsonObject();
                String content = msg.has("content") ? msg.get("content").getAsString() : "";
                String sender = msg.has("sender") ? msg.get("sender").getAsString() : "?";
                if (content.isEmpty() || !shouldReply(content)) {
                    continue;
                }
                String reply = generateReply(sender, content);
                if (reply != null && !reply.isEmpty() && replySink != null) {
                    rememberReply(reply);
                    replySink.reply(reply);
                }
            } catch (Exception e) {
                Logging.LOG.log(Level.FINE, "对话处理失败", e);
            }
        }
    }

    /**
     * 判断是否需要回复：包含触发词或 AI 名字
     */
    private boolean shouldReply(String content) {
        String lower = content.toLowerCase();
        if (aiName != null && !aiName.isEmpty()
                && lower.contains(aiName.toLowerCase())) {
            return true;
        }
        if (trigger != null && !trigger.isEmpty()
                && lower.contains(trigger.toLowerCase())) {
            return true;
        }
        return false;
    }

    private String generateReply(String sender, String content) {
        try {
            String system = "你是 Minecraft 游戏中的 AI 助手【" + aiName + "】。"
                    + "玩家会和你聊天，请用简短的中文回复（一两句话），"
                    + "可以表达你在帮忙或正在做什么。";
            return llm.chat(system, sender + " 说: " + content);
        } catch (Exception e) {
            Logging.LOG.log(Level.WARNING, "AI 回复生成失败", e);
            return null;
        }
    }

    /**
     * 防止回复循环：记录最近回复，避免对自身回复再回复
     */
    private void rememberReply(String reply) {
        recentReplies.addLast(reply);
        while (recentReplies.size() > MAX_REPLY_HISTORY) {
            recentReplies.removeFirst();
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
}
