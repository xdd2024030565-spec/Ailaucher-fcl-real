package com.tungsten.fcl.ai;

import android.app.Activity;
import android.widget.Toast;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tungsten.fcl.game.FCLGameRepository;
import com.tungsten.fcl.game.LauncherHelper;
import com.tungsten.fcl.setting.Accounts;
import com.tungsten.fcl.setting.Profile;
import com.tungsten.fcl.setting.Profiles;
import com.tungsten.fcl.setting.VersionSetting;
import com.tungsten.fclcore.auth.Account;
import com.tungsten.fclcore.util.Logging;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

/**
 * 第二实例管理器 —— 远程假人（小号）支持
 *
 * 适用于无规则服务器等远程环境：服务器视角下，小号是真实玩家。
 *
 * 实现原理（不动 FCL 启动核心）：
 * 1. 自动准备版本副本（{版本}-AiAlt），开启版本隔离；
 * 2. 在副本运行目录写入端口文件（ai_bridge_port.txt）；
 * 3. Bridge Mod 启动时从端口文件读取专属端口（默认第二实例 25581）；
 * 4. 用 LauncherHelper 以小号账号启动副本。
 */
public final class AltInstanceManager {

    /** 版本副本后缀 */
    public static final String ALT_SUFFIX = "-AiAlt";

    private AltInstanceManager() {
    }

    /**
     * 启动 AI 第二实例（小号）
     */
    public static void launchAltInstance(Activity activity) {
        if (activity == null) {
            return;
        }
        AiConfig config = AiConfig.getInstance(activity);

        // 1. 环境检查
        Profile profile = Profiles.getSelectedProfile();
        if (profile == null || profile.getSelectedVersion() == null) {
            toast(activity, "请先在主界面选择游戏版本");
            return;
        }
        final String baseVersion = profile.getSelectedVersion();

        // 2. 查找小号（第一个非当前账号）
        final Account alt = findAltAccount();
        if (alt == null) {
            toast(activity, "请先在账户页面添加第二个账号（小号）");
            return;
        }

        final int altPort = config.getAltPort();
        final String altVersionId = baseVersion + ALT_SUFFIX;
        toast(activity, "正在准备 AI 第二实例...");

        // 3. 后台准备（复制版本可能耗时）
        new Thread(() -> {
            try {
                FCLGameRepository repository = profile.getRepository();

                // 3.1 确保版本副本存在
                if (!repository.hasVersion(altVersionId)) {
                    copyVersion(repository, baseVersion, altVersionId);
                    repository.refreshVersions();
                }

                // 3.2 副本设置：确保运行目录隔离
                VersionSetting setting = profile.getVersionSetting(altVersionId);
                VersionSetting global = profile.getGlobalVersionSetting();
                if (global == null || !global.isIsolateGameDir()) {
                    setting.setUsesGlobal(false);
                    setting.setIsolateGameDir(true);
                }

                // 3.3 写入端口文件
                File runDir = repository.getRunDirectory(altVersionId);
                runDir.mkdirs();
                File portFile = new File(runDir, AiBridgeInstaller.PORT_FILE_NAME);
                Files.write(portFile.toPath(), String.valueOf(altPort).getBytes(StandardCharsets.UTF_8));

                // 3.4 UI 线程启动
                activity.runOnUiThread(() -> {
                    try {
                        new LauncherHelper(activity, profile, alt, altVersionId).launch();
                    } catch (Exception e) {
                        Logging.LOG.log(Level.WARNING, "AI 第二实例启动失败", e);
                        toast(activity, "启动失败: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                Logging.LOG.log(Level.WARNING, "AI 第二实例准备失败", e);
                activity.runOnUiThread(() -> toast(activity, "准备失败: " + e.getMessage()));
            }
        }).start();
    }

    /**
     * 查找小号：第一个非当前选中的账号
     */
    public static Account findAltAccount() {
        Account current = Accounts.getSelectedAccount();
        for (Account account : Accounts.getAccounts()) {
            if (account != current) {
                return account;
            }
        }
        return null;
    }

    /**
     * 复制版本目录（from → to）
     *
     * - 递归复制全部文件
     * - 修改 JSON 的 id 字段
     * - 保留原 jar 文件名，并固定 "jar" 字段指向基础版本
     */
    private static void copyVersion(FCLGameRepository repository, String from, String to) throws IOException {
        File fromDir = repository.getVersionRoot(from);
        File toDir = repository.getVersionRoot(to);
        if (!fromDir.exists() || !fromDir.isDirectory()) {
            throw new IOException("源版本目录不存在: " + from);
        }

        copyRecursively(fromDir, toDir);

        // 修改 JSON
        File fromJson = new File(toDir, from + ".json");
        if (!fromJson.exists()) {
            throw new IOException("版本 JSON 缺失: " + from);
        }
        String content = new String(Files.readAllBytes(fromJson.toPath()), StandardCharsets.UTF_8);
        JsonObject json = JsonParser.parseString(content).getAsJsonObject();
        json.addProperty("id", to);
        if (!json.has("jar")) {
            json.addProperty("jar", from);
        }

        File toJson = new File(toDir, to + ".json");
        Files.write(toJson.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
        if (!fromJson.equals(toJson)) {
            fromJson.delete();
        }

        Logging.LOG.info("AI 版本副本已创建: " + from + " → " + to);
    }

    private static void copyRecursively(File from, File to) throws IOException {
        if (from.isDirectory()) {
            to.mkdirs();
            File[] children = from.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursively(child, new File(to, child.getName()));
                }
            }
        } else {
            File parent = to.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void toast(Activity activity, String msg) {
        activity.runOnUiThread(() ->
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show());
    }
}
