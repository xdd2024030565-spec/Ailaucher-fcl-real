package com.tungsten.fcl.ai;

import android.content.Context;

import com.tungsten.fclcore.game.GameRepository;
import com.tungsten.fclcore.game.Version;
import com.tungsten.fclcore.util.Logging;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.logging.Level;

/**
 * AI Bridge Mod 自动注入器 —— AI Minecraft Launcher 集成层
 *
 * 在游戏启动前，把内置在 APK assets 中的 AI Bridge Fabric Mod
 * 复制到游戏版本的 mods 目录，使游戏启动后自动开启 HTTP 桥接服务。
 *
 * 资产路径: assets/ai/ai-bridge.jar
 */
public class AiBridgeInstaller {

    /** APK 内资产路径 */
    public static final String ASSET_PATH = "ai/ai-bridge.jar";
x20   /** mods 目录下的目标文件名 */
    public static final String BRIDGE_JAR_NAME = "ai-bridge.jar";

    /**
     * 按配置安装 AI Bridge Mod（若开关关闭则跳过）
     *
     * @return true 表示已安装或无需安装
     */
    public static boolean installIfEnabled(Context context, GameRepository repository, Version version) {
        if (!AiConfig.getInstance(context).isAutoInstallBridge()) {
            return true;
        }
        return install(context, repository, version);
    }

    /**
     * 把 AI Bridge Mod 复制到指定版本的 mods 目录
     *
     * @return true 安装成功
     */
    public static boolean install(Context context, GameRepository repository, Version version) {
        try {
            Path modsPath = repository.getModsDirectory(version.getId());
            if (modsPath == null) return false;

            File modsDir = modsPath.toFile();
            if (!modsDir.exists() && !modsDir.mkdirs()) {
                Logging.LOG.log(Level.WARNING, "无法创建 mods 目录: " + modsDir);
                return false;
            }

            File dest = new File(modsDir, BRIDGE_JAR_NAME);
            if (dest.exists() && dest.length() > 0) {
                return true;
            }

            // 从 assets 复制
            try (InputStream is = context.getAssets().open(ASSET_PATH)) {
                try (OutputStream os = new FileOutputStream(dest)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        os.write(buffer, 0, read);
                    }
                    os.flush();
                }
            }
            Logging.LOG.info("AI Bridge Mod 已注入: " + dest.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Logging.LOG.log(Level.WARNING, "AI Bridge Mod 注入失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 检查指定版本是否已安装 AI Bridge Mod
     */
    public static boolean hasBridge(GameRepository repository, Version version) {
        try {
            Path modsPath = repository.getModsDirectory(version.getId());
            if (modsPath == null) return false;
            File dest = new File(modsPath.toFile(), BRIDGE_JAR_NAME);
            return dest.exists() && dest.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查内置资产是否存在
     */
    public static boolean hasBundledBridge(Context context) {
        try (InputStream is = context.getAssets().open(ASSET_PATH)) {
            return is != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 移除已注入的 AI Bridge Mod
     */
    public static boolean remove(GameRepository repository, Version version) {
        try {
            Path modsPath = repository.getModsDirectory(version.getId());
            if (modsPath == null) return false;
            File dest = new File(modsPath.toFile(), BRIDGE_JAR_NAME);
            return !dest.exists() || dest.delete();
        } catch (Exception e) {
            return false;
        }
    }
}
