package com.tungsten.fcl.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.tungsten.fcl.R;
import com.tungsten.fcl.activity.MainActivity;
import com.tungsten.fcl.ai.controller.DecisionEngine;
import com.tungsten.fcl.ai.controller.FakePlayerClient;
import com.tungsten.fcl.ai.controller.GameApiClient;
import com.tungsten.fcl.ai.controller.LlmClient;
import com.tungsten.fclcore.util.Logging;

import java.util.logging.Level;

/**
 * AI 控制器后台服务 —— AI Minecraft Launcher 集成层
 *
 * 根据配置模式驱动 AI：
 * - 自控模式 (MODE_SELF)：AI 驾驶你的角色（本地/任意服务器）
 * - 本地假人 (MODE_LOCAL_FAKE)：驱动服务端真·假人（本地存档）
 * - 远程假人 (MODE_REMOTE_ALT)：驱动第二实例小号（无规则服务器）
 *
 * 对话系统全模式常驻（@触发词或提及 AI 名字）。
 */
public class AiControllerService extends Service {

    public static final String ACTION_START = "com.tungsten.fcl.ai.action.START";
    public static final String ACTION_STOP = "com.tungsten.fcl.ai.action.STOP";

    /** 控制器运行状态（供 UI 查询） */
    public static volatile boolean running = false;

    /** 最近一次决策结果（供 UI 展示） */
    public static volatile String lastDecision = "";

    private static final String CHANNEL_ID = "fcl_ai_controller";
    private static final int NOTIFICATION_ID = 10901;

    /** 假人模式系统提示词 */
    private static final String FAKE_SYSTEM_PROMPT =
            "You are an AI-controlled Minecraft fake player (bot). "
            + "Return ONLY a JSON array of actions.\n"
            + "Available actions:\n"
            + "- goto: {x, y, z}  (move to position)\n"
            + "- look: {yaw, pitch}\n"
            + "- lookAt: {x, y, z}\n"
            + "- mine: {x, y, z}  (break block at position)\n"
            + "- attack: {radius: 6}  (attack nearest mob)\n"
            + "- jump: {}\n"
            + "- chat: {message}\n"
            + "Rules:\n"
            + "- Only one or two actions per cycle.\n"
            + "- Example: [{\"action\":\"attack\",\"radius\":6}]\n"
            + "- If a hostile mob is nearby, attack it first.\n"
            + "- Stay near the player when not doing anything.";

    private Thread worker;

    private final Gson gson = new Gson();

    private GameApiClient gameApi;
    private LlmClient llmClient;
    private DecisionEngine engine;
    private FakePlayerClient fakePlayerClient;
    private DialogueManager dialogueManager;
    private DecisionEngine altEngine;

    private String lastSignature = "";
    private long lastFakeSpawnAttempt = 0;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopLoop();
            return START_NOT_STICKY;
        }
        startLoop();
        return START_STICKY;
    }

    private void startLoop() {
        if (running) {
            return;
        }
        running = true;
        startForegroundNotice();
        worker = new Thread(this::loop, "fcl-ai-controller");
        worker.setDaemon(true);
        worker.start();
    }

    private void stopLoop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        stopForeground(true);
        stopSelf();
    }

    /**
     * 控制器主循环：按模式分派
     */
    private void loop() {
        Logging.LOG.info("AI 控制器已启动");
        while (running) {
            AiConfig config = AiConfig.getInstance(this);
            try {
                ensureEngines(config);

                // 对话轮询（所有模式，若启用）
                if (config.isDialogueEnabled() && dialogueManager != null) {
                    dialogueManager.tick();
                }

                if (!config.hasApiKey()) {
                    // 无 Key：仅健康检查
                    if (gameApi != null && gameApi.isConnected()) {
                        lastDecision = "AI Bridge 在线，请配置 API Key";
                    }
                } else {
                    switch (config.getMode()) {
                        case AiConfig.MODE_LOCAL_FAKE:
                            runLocalFakeCycle(config);
                            break;
                        case AiConfig.MODE_REMOTE_ALT:
                            runRemoteAltCycle(config);
                            break;
                        case AiConfig.MODE_SELF:
                        default:
                            runSelfCycle(config);
                            break;
                    }
                }
            } catch (Exception e) {
                lastDecision = "循环异常: " + e.getMessage();
                Logging.LOG.log(Level.FINE, "AI 决策循环异常", e);
            }
            try {
                Thread.sleep(AiConfig.getInstance(this).getCycleIntervalMs());
            } catch (InterruptedException e) {
                break;
            }
        }
        running = false;
        Logging.LOG.info("AI 控制器已停止");
    }

    /**
     * 按配置构建/重建引擎（配置变化时自动重建）
     */
    private void ensureEngines(AiConfig config) {
        String signature = config.getApiKey() + "|" + config.getModel() + "|"
                + config.getBaseUrl() + "|" + config.getBridgePort() + "|"
                + config.getMode() + "|" + config.getFakePlayerName();

        if (engine != null && signature.equals(lastSignature)) {
            engine.setCurrentTask(config.getTask());
            engine.setVisualMode(config.isVisualMode());
            engine.setMemoryEnabled(config.isMemoryEnabled());
            return;
        }

        gameApi = new GameApiClient(config.getBridgePort());
        llmClient = new LlmClient(config.getApiKey(), config.getModel(), config.getBaseUrl());
        engine = new DecisionEngine(gameApi, llmClient);
        engine.setCurrentTask(config.getTask());
        engine.setVisualMode(config.isVisualMode());
        engine.setMemoryEnabled(config.isMemoryEnabled());

        // 假人模式使用专用系统提示词
        if (config.getMode() == AiConfig.MODE_LOCAL_FAKE) {
            engine.setSystemPromptOverride(FAKE_SYSTEM_PROMPT);
        } else {
            engine.setSystemPromptOverride(null);
        }

        fakePlayerClient = new FakePlayerClient(config.getBridgePort());

        // 对话管理器（回复途径按模式选择）
        dialogueManager = new DialogueManager(config.getBridgePort(), llmClient,
                config.getFakePlayerName(), config.getDialogueTrigger());
        if (config.getMode() == AiConfig.MODE_LOCAL_FAKE) {
            dialogueManager.useFakePlayerReply(fakePlayerClient, config.getFakePlayerName());
        } else {
            dialogueManager.usePlayerReply();
        }

        // 远程假人引擎（配置变化时重建）
        altEngine = null;
        lastSignature = signature;
    }

    /**
     * 自控模式：AI 直接控制你的角色
     */
    private void runSelfCycle(AiConfig config) {
        DecisionEngine.DecisionResult result = engine.runDecisionCycle();
        lastDecision = "[自控] " + result;
    }

    /**
     * 本地假人模式：确保假人在线（玩家附近），动作用于假人
     */
    private void runLocalFakeCycle(AiConfig config) {
        String name = config.getFakePlayerName();
        try {
            if (!fakePlayerClient.isOnline(name)) {
                // 节流：每 10 秒最多尝试生成一次
                long now = System.currentTimeMillis();
                if (now - lastFakeSpawnAttempt < 10000) {
                    return;
                }
                lastFakeSpawnAttempt = now;

                // 在玩家附近生成（+2 格）
                GameApiClient.GameStateResponse state = gameApi.getGameState();
                if (state != null && state.connected && state.position != null) {
                    double px = state.position.get(0) + 2;
                    double py = state.position.get(1);
                    double pz = state.position.get(2);
                    boolean ok = fakePlayerClient.spawn(name, px, py, pz);
                    lastDecision = ok ? "[本地假人] 已生成: " + name : "[本地假人] 生成失败";
                }
                return;
            }

            // 动作用于假人（替换执行层）
            engine.setActionSink((action, params) -> {
                JsonObject json = gson.toJsonTree(params).getAsJsonObject();
                json.addProperty("action", action);
                fakePlayerClient.action(name, json);
            });

            DecisionEngine.DecisionResult result = engine.runDecisionCycle();
            lastDecision = "[本地假人] " + result;
        } catch (Exception e) {
            lastDecision = "[本地假人] 异常: " + e.getMessage();
        }
    }

    /**
     * 远程假人模式：驱动第二实例（小号）
     */
    private void runRemoteAltCycle(AiConfig config) {
        try {
            if (altEngine == null) {
                altEngine = new DecisionEngine(
                        new GameApiClient(config.getAltPort()),
                        new LlmClient(config.getApiKey(), config.getModel(), config.getBaseUrl()));
            }
            altEngine.setCurrentTask(config.getTask());
            altEngine.setVisualMode(config.isVisualMode());
            altEngine.setMemoryEnabled(config.isMemoryEnabled());

            DecisionEngine.DecisionResult result = altEngine.runDecisionCycle();
            lastDecision = "[远程假人] " + result;
        } catch (Exception e) {
            lastDecision = "[远程假人] 异常: " + e.getMessage();
        }
    }

    private void startForegroundNotice() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "AI Controller", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }

        Intent notifyIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AI 控制器运行中")
                .setContentText("模式: " + AiConfig.getInstance(this).getModeName())
                .setSmallIcon(R.drawable.ic_ai)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        try {
            startForeground(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            Logging.LOG.log(Level.WARNING, "AI 前台服务启动失败", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }
}
