package com.tungsten.fcl.ai.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 客户端 —— 文本对话 + 多模态（截图）
 *
 * 由原 AI-Minecraft-Launcher 的 LlmClient 移植并重写：
 * 去除 OkHttp/Retrofit 依赖，改用 HttpURLConnection + Gson。
 *
 * 支持 OpenAI 兼容 API：OpenAI / DeepSeek / 其他兼容端点。
 */
public class LlmClient {

    private final Gson gson = new Gson();
    private final String apiKey;
    private final String model;
    private final String baseUrl;

    public LlmClient(String apiKey, String model) {
        this(apiKey, model, "https://api.openai.com/v1");
    }

    public LlmClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = normalizeBaseUrl(baseUrl);
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "https://api.openai.com/v1";
        }
        String result = url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    // ==================== 公开方法 ====================

    /**
     * 文本对话：向 LLM 发送 system + user 提示，返回文本回复
     */
    public String chat(String systemPrompt, String userPrompt) throws Exception {
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        return callChat(messages, 2000);
    }

    /**
     * 多模态对话：附加 Base64 PNG 截图
     */
    public String chatWithImage(String systemPrompt, String userPrompt, String base64Image) throws Exception {
        JsonArray messages = new JsonArray();

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        messages.add(systemMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        JsonArray content = new JsonArray();

        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", userPrompt);
        content.add(textPart);

        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", "data:image/png;base64," + base64Image);
        imagePart.add("image_url", imageUrl);
        content.add(imagePart);

        userMsg.add("content", content);
        messages.add(userMsg);

        return callChat(messages, 2000);
    }

    /**
     * 获取结构化的动作列表
     */
    public List<Map<String, Object>> getActions(String systemPrompt, String userPrompt) throws Exception {
        String response = chat(systemPrompt, userPrompt);
        String json = extractJsonArray(response);
        if (json == null) {
            throw new Exception("LLM did not return valid JSON array: " + response);
        }
        try {
            List<Map<String, Object>> result = gson.fromJson(json,
                    new TypeToken<List<Map<String, Object>>>() { }.getType());
            return result != null ? result : new ArrayList<>();
        } catch (Exception e) {
            throw new Exception("Failed to parse JSON actions: " + e.getMessage(), e);
        }
    }

    /**
     * 获取结构化的动作列表（含视觉）
     */
    public List<Map<String, Object>> getActionsWithImage(String systemPrompt, String userPrompt,
                                                         String base64Image) throws Exception {
        String response = chatWithImage(systemPrompt, userPrompt, base64Image);
        String json = extractJsonArray(response);
        if (json == null) {
            throw new Exception("LLM did not return valid JSON array: " + response);
        }
        try {
            List<Map<String, Object>> result = gson.fromJson(json,
                    new TypeToken<List<Map<String, Object>>>() { }.getType());
            return result != null ? result : new ArrayList<>();
        } catch (Exception e) {
            throw new Exception("Failed to parse JSON actions: " + e.getMessage(), e);
        }
    }

    // ==================== 内部方法 ====================

    private JsonObject message(String role, String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", role);
        msg.addProperty("content", content);
        return msg;
    }

    private String callChat(JsonArray messages, int maxTokens) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("temperature", 0.7);
        body.addProperty("max_tokens", maxTokens);

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + "/chat/completions").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(gson.toJson(body).getBytes("UTF-8"));
                os.flush();
            }

            try (InputStream is = conn.getInputStream()) {
                String respBody = readAll(is);
                JsonObject json = JsonParser.parseString(respBody).getAsJsonObject();
                JsonArray choices = json.getAsJsonArray("choices");
                if (choices == null || choices.size() == 0) {
                    throw new Exception("Empty LLM response");
                }
                return choices.get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString();
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

    /**
     * 从 LLM 回复中提取 JSON 数组（支持 ```json 代码块）
     */
    private String extractJsonArray(String text) {
        Pattern jsonBlockRegex = Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```");
        Matcher match = jsonBlockRegex.matcher(text);
        if (match.find()) {
            return match.group(1).trim();
        }

        Pattern arrayRegex = Pattern.compile("(\\[.*\\])", Pattern.DOTALL);
        Matcher arrayMatch = arrayRegex.matcher(text);
        if (arrayMatch.find()) {
            return arrayMatch.group(1).trim();
        }

        return null;
    }
}
