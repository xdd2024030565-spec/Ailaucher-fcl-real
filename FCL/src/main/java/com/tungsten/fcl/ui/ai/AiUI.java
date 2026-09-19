package com.tungsten.fcl.ui.ai;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;

import com.tungsten.fcl.FCLApp;
import com.tungsten.fcl.R;
import com.tungsten.fcl.ai.AiBridgeInstaller;
import com.tungsten.fcl.ai.AiConfig;
import com.tungsten.fcl.ai.AiControllerService;
import com.tungsten.fcl.ai.AltInstanceManager;
import com.tungsten.fcl.setting.Profile;
import com.tungsten.fcl.setting.Profiles;
import com.tungsten.fclcore.game.GameRepository;
import com.tungsten.fclcore.game.Version;
import com.tungsten.fclcore.task.Task;
import com.tungsten.fclcore.util.Logging;
import com.tungsten.fcllibrary.component.ui.FCLCommonUI;
import com.tungsten.fcllibrary.component.view.FCLButton;
import com.tungsten.fcllibrary.component.view.FCLTextView;

import java.util.logging.Level;

/**
 * AI 控制器页面 —— AI Minecraft Launcher 集成层
 *
 * 三模式切换（自控 / 本地假人 / 远程假人）、LLM 配置、
 * 假人设置、对话开关、启停控制器、启动第二实例。
 */
public class AiUI extends FCLCommonUI implements View.OnClickListener {

    private static final String[] MODE_NAMES = {"自控模式", "本地假人", "远程假人"};

    private EditText apiKeyInput;
    private EditText baseUrlInput;
    private EditText modelInput;
    private EditText taskInput;
    private EditText portInput;
    private EditText cycleInput;
    private EditText fakeNameInput;
    private EditText altPortInput;
    private EditText dialogueTriggerInput;
    private Switch autoBridgeSwitch;
    private Switch visualSwitch;
    private Switch memorySwitch;
    private Switch dialogueSwitch;
    private Spinner modeSpinner;
    private FCLButton saveButton;
    private FCLButton installBridgeButton;
    private FCLButton startButton;
    private FCLButton stopButton;
    private FCLButton launchAltButton;
    private FCLTextView statusView;

    public AiUI(Context context, int id) {
        super(context, id);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        apiKeyInput = findViewById(R.id.et_api_key);
        baseUrlInput = findViewById(R.id.et_base_url);
        modelInput = findViewById(R.id.et_model);
        taskInput = findViewById(R.id.et_task);
        portInput = findViewById(R.id.et_port);
        cycleInput = findViewById(R.id.et_cycle);
        fakeNameInput = findViewById(R.id.et_fake_name);
        altPortInput = findViewById(R.id.et_alt_port);
        dialogueTriggerInput = findViewById(R.id.et_dialogue_trigger);
        autoBridgeSwitch = findViewById(R.id.sw_auto_bridge);
        visualSwitch = findViewById(R.id.sw_visual);
        memorySwitch = findViewById(R.id.sw_memory);
        dialogueSwitch = findViewById(R.id.sw_dialogue);
        modeSpinner = findViewById(R.id.sp_mode);
        saveButton = findViewById(R.id.btn_save);
        installBridgeButton = findViewById(R.id.btn_install_bridge);
        startButton = findViewById(R.id.btn_start);
        stopButton = findViewById(R.id.btn_stop);
        launchAltButton = findViewById(R.id.btn_launch_alt);
        statusView = findViewById(R.id.tv_ai_status);

        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_item, MODE_NAMES);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modeAdapter);

        saveButton.setOnClickListener(this);
        installBridgeButton.setOnClickListener(this);
        startButton.setOnClickListener(this);
        stopButton.setOnClickListener(this);
        launchAltButton.setOnClickListener(this);

        loadConfig();
        updateStatus();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    public Task<?> refresh(Object... param) {
        return null;
    }

    private void loadConfig() {
        AiConfig config = AiConfig.getInstance(getContext());
        modeSpinner.setSelection(config.getMode());
        apiKeyInput.setText(config.getApiKey());
        baseUrlInput.setText(config.getBaseUrl());
        modelInput.setText(config.getModel());
        taskInput.setText(config.getTask());
        portInput.setText(String.valueOf(config.getBridgePort()));
        cycleInput.setText(String.valueOf(config.getCycleIntervalMs()));
        fakeNameInput.setText(config.getFakePlayerName());
        altPortInput.setText(String.valueOf(config.getAltPort()));
        dialogueTriggerInput.setText(config.getDialogueTrigger());
        autoBridgeSwitch.setChecked(config.isAutoInstallBridge());
        visualSwitch.setChecked(config.isVisualMode());
        memorySwitch.setChecked(config.isMemoryEnabled());
        dialogueSwitch.setChecked(config.isDialogueEnabled());
    }

    @Override
    public void onClick(View view) {
        if (view == saveButton) {
            saveConfig();
        } else if (view == installBridgeButton) {
            installBridge();
        } else if (view == startButton) {
            startController();
        } else if (view == stopButton) {
            stopController();
        } else if (view == launchAltButton) {
            launchAltInstance();
        }
    }

    private void saveConfig() {
        AiConfig config = AiConfig.getInstance(getContext());
        config.setMode(modeSpinner.getSelectedItemPosition());
        config.setApiKey(apiKeyInput.getText().toString());
        config.setBaseUrl(baseUrlInput.getText().toString());
        config.setModel(modelInput.getText().toString());
        config.setTask(taskInput.getText().toString());
        config.setBridgePort(parseInt(portInput.getText().toString(), 25580));
        config.setCycleIntervalMs(parseInt(cycleInput.getText().toString(), 3000));
        config.setFakePlayerName(fakeNameInput.getText().toString());
        config.setAltPort(parseInt(altPortInput.getText().toString(), 25581));
        config.setDialogueTrigger(dialogueTriggerInput.getText().toString());
        config.setAutoInstallBridge(autoBridgeSwitch.isChecked());
        config.setVisualMode(visualSwitch.isChecked());
        config.setMemoryEnabled(memorySwitch.isChecked());
        config.setDialogueEnabled(dialogueSwitch.isChecked());
        Toast.makeText(getContext(), "AI 配置已保存", Toast.LENGTH_SHORT).show();
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private void installBridge() {
        Context context = getContext();
        Profile profile = Profiles.getSelectedProfile();
        String versionId = profile == null ? null : profile.getSelectedVersion();
        if (versionId == null) {
            Toast.makeText(context, "请先在“管理”中选择一个游戏版本", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            GameRepository repository = profile.getRepository();
            Version version = repository.getVersion(versionId);
            if (!AiBridgeInstaller.hasBundledBridge(context)) {
                Toast.makeText(context, "APK 内缺少 ai-bridge.jar，请先构建並放入 assets/ai/", Toast.LENGTH_LONG).show();
                return;
            }
            boolean ok = AiBridgeInstaller.install(context, repository, version);
            Toast.makeText(context, ok ? "AI Bridge Mod 已安装到: " + versionId : "安装失败", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Logging.LOG.log(Level.WARNING, "安装 AI Bridge Mod 失败", e);
            Toast.makeText(context, "安装失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 启动 AI 第二实例（远程假人：小号）
     */
    private void launchAltInstance() {
        Context context = getContext();
        Activity activity = context instanceof Activity ? (Activity) context : FCLApp.getActivity();
        if (activity == null) {
            Toast.makeText(context, "无法获取 Activity", Toast.LENGTH_SHORT).show();
            return;
        }
        // 先保存配置（确保端口等参数生效）
        saveConfig();
        AltInstanceManager.launchAltInstance(activity);
    }

    private void startController() {
        Context context = getContext();
        Intent intent = new Intent(context, AiControllerService.class);
        intent.setAction(AiControllerService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            Toast.makeText(context, "AI 控制器已启动", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(context, "启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        updateStatus();
    }

    private void stopController() {
        Context context = getContext();
        try {
            Intent intent = new Intent(context, AiControllerService.class);
            intent.setAction(AiControllerService.ACTION_STOP);
            context.startService(intent);
            Toast.makeText(context, "AI 控制器已停止", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(context, "停止失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        updateStatus();
    }

    private void updateStatus() {
        if (statusView == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(AiControllerService.running ? "AI 控制器：运行中" : "AI 控制器：已停止");
        String decision = AiControllerService.lastDecision;
        if (decision != null && !decision.isEmpty()) {
            sb.append("\n").append(decision);
        }
        statusView.setText(sb.toString());
    }
}
