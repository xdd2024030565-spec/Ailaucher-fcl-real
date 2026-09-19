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
import com.tungsten.fclcore.util.Logging;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Level;

/**
 * AI 控制器后台服务 —— AI Minecraft Launcher 集成层
 *
 * 连接游戏内 AI Bridge Mod 的 HTTP 接口（默认 http://127.0.0.1:25580），
 * 循环获取游戏状态，后续版本接入 LLM 决策引擎执行动作。
 */
public class AiControllerService extends Service {

    public static final String ACTION_START = "com.tungsten.fcl.ai.action.START";
    public static final String ACTION_STOP = "com.tungsten.fcl.ai.action.STOP";

    /** 控制器运行状态（供 UI 查询） */
    public static volatile boolean running = false;

    private static final String CHANNEL_ID = "fcl_ai_controller";
    private static final int NOTIFICATION_ID = 10901;

    private Thread worker;

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
     * 控制器主循环：查询游戏状态
     */
    private void loop() {
        while (running) {
            try {
                AiConfig config = AiConfig.getInstance(this);
                int port = config.getBridgePort();
                String state = httpGet("http://127.0.0.1:" + port + "/api/state", 3000);
                if (state != null) {
                    Logging.LOG.fine("AI controller state: " + state);
                }
            } catch (Exception ignored) {
                // 游戏未启动或 Bridge 未就绪，静默重试
            }
            try {
                Thread.sleep(AiConfig.getInstance(this).getCycleIntervalMs());
            } catch (InterruptedException e) {
                break;
            }
        }
        running = false;
    }

    private String httpGet(String urlStr, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                return new String(bos.toByteArray(), "UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
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
                .setContentText("正在连接 Minecraft AI Bridge")
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
