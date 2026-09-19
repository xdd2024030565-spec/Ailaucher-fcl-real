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

import com.tungsten.fcl.R;
import com.tungsten.fcl.activity.MainActivity;
import com.tungsten.fcl.ai.controller.DecisionEngine;
import com.tungsten.fcl.ai.controller.GameApiClient;
import com.tungsten.fcl.ai.controller.LlmClient;
import com.tungsten.fclcore.util.Logging;

import java.util.logging.Level;

/**
 * AI 控制器后台服务 —— AI Minecraft Launcher 集成层
 *
 * 连接游戏内 AI Bridge Mod 的 HTTP 接口（默认 http://127.0.0.1:25580），
 * 通过 LLM 决策循环自动控制游戏角色（AI 玩 Minecraft）。
 *
 * 架构对齐原 AI-Minecraft-Launcher：
 * GameApiClient (游戏接口) + LlmClient (LLM) + DecisionEngine (决策循环)。
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

    private Thread worker;

    private GameApiClient gameApi;
    private LlmClient llmClient;
    private DecisionEngine engine;
    private String lastSignature = "";

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
     * 控制器主循环：LLM 决策 → 执行动作 → 记忆
     */
    private void loop() {
        Logging.LOG.info("AI 控制器已启动");
        while (running) {
            AiConfig config = AiConfig.getInstance(this);
            try {
                ensureEngine(config);
                if (config.hasApiKey()) {
                    DecisionEngine.DecisionResult result = engine.runDecisionCycle();
                    lastDecision = result.toString();
                    Logging.LOG.fine("AI 决策: " + result);
                } else if (gameApi.isConnected()) {
                    lastDecision = "AI Bridge 在线，请在 AI 页面配置 API Key";
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
     * 按配置构建/重建决策引擎（配置变化时自动重建）
     */
    private void ensureEngine(AiConfig config) {
        String signature = config.getApiKey() + "|" + config.getModel() + "|"
                + config.getBaseUrl() + "|" + config.getBridgePort();

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
        lastSignature = signature;
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
                .setContentText("正在控制 Minecraft 角色")
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
