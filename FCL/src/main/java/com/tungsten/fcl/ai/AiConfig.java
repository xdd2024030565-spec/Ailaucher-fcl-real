package com.tungsten.fcl.ai;

import android.content.Context;
import android.content.SharedPreferences;

import com.tungsten.fcl.FCLApp;

/**
 * AI 控制器配置 —— AI Minecraft Launcher 集成层
 *
 * 持久化 AI 功能配置：API Key、模型、任务描述、桥接端口等。
 * 对应原 AI-Minecraft-Launcher 的 AiConfig，适配 FCL 本体。
 */
public class AiConfig {

    private static final String PREFS_NAME = "fcl_ai_config";
    private static AiConfig instance;

    private final SharedPreferences prefs;

    private AiConfig(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized AiConfig getInstance() {
        if (instance == null) {
            instance = new AiConfig(FCLApp.getAppContext());
        }
        return instance;
    }

    public static AiConfig getInstance(Context context) {
        if (instance == null) {
            instance = new AiConfig(context);
        }
        return instance;
    }

    // === 总开关 ===

    /** 启动游戏时自动注入 AI Bridge Mod */
    public boolean isAutoInstallBridge() {
        return prefs.getBoolean("auto_install_bridge", true);
    }

    public void setAutoInstallBridge(boolean value) {
        prefs.edit().putBoolean("auto_install_bridge", value).apply();
    }

    // === 桥接端口 ===

    public int getBridgePort() {
        return prefs.getInt("bridge_port", 25580);
    }

    public void setBridgePort(int port) {
        prefs.edit().putInt("bridge_port", port).apply();
    }

    // === LLM 配置 ===

    public String getApiKey() {
        return prefs.getString("api_key", "");
    }

    public void setApiKey(String key) {
        prefs.edit().putString("api_key", key == null ? "" : key.trim()).apply();
    }

    public String getBaseUrl() {
        return prefs.getString("base_url", "https://api.openai.com/v1");
    }

    public void setBaseUrl(String url) {
        prefs.edit().putString("base_url", url == null ? "" : url.trim()).apply();
    }

    public String getModel() {
        return prefs.getString("model", "gpt-4o-mini");
    }

    public void setModel(String model) {
        prefs.edit().putString("model", model == null ? "" : model.trim()).apply();
    }

    // === 任务 ===

    public String getTask() {
        return prefs.getString("task", "探索世界并生存下去");
    }

    public void setTask(String task) {
        prefs.edit().putString("task", task == null ? "" : task).apply();
    }

    public int getCycleIntervalMs() {
        return prefs.getInt("cycle_interval", 3000);
    }

    public void setCycleIntervalMs(int ms) {
        prefs.edit().putInt("cycle_interval", Math.max(500, ms)).apply();
    }

    // === 高级选项 ===

    /** 视觉模式：截图 + 多模态 LLM */
    public boolean isVisualMode() {
        return prefs.getBoolean("visual_mode", false);
    }

    public void setVisualMode(boolean value) {
        prefs.edit().putBoolean("visual_mode", value).apply();
    }

    /** 记忆系统 */
    public boolean isMemoryEnabled() {
        return prefs.getBoolean("memory", true);
    }

    public void setMemoryEnabled(boolean value) {
        prefs.edit().putBoolean("memory", value).apply();
    }

    /** 多智能体模式 */
    public boolean isMultiAgentEnabled() {
        return prefs.getBoolean("multi_agent", false);
    }

    public void setMultiAgentEnabled(boolean value) {
        prefs.edit().putBoolean("multi_agent", value).apply();
    }

    public boolean hasApiKey() {
        String key = getApiKey();
        return key != null && !key.isEmpty();
    }
}
