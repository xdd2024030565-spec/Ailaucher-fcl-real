package com.tungsten.fcl.ai;

import android.app.Activity;
import android.view.View;
import android.widget.Toast;

import com.tungsten.fcl.R;
import com.tungsten.fcl.activity.MainActivity;
import com.tungsten.fcl.ui.UIManager;
import com.tungsten.fclcore.util.Logging;
import com.tungsten.fcllibrary.component.view.FCLMenuView;

import java.util.logging.Level;

/**
 * AI 菜单钩子 —— 无侵入接入 FCL 主界面菜单（修复点击无效）
 *
 * 与 MainActivity 中其它菜单（home/manage/...）使用完全相同的链路：
 *   点击 → setSelected(true) → OnSelectListener.onSelect → 切换 UIManager 页面
 *
 * 由 FCLApp 在多个生命周期时机调用（幂等），避免单一时机的挂载遗漏。
 */
public final class AiMenuHook {

    private AiMenuHook() {
    }

    /**
     * 类型判断后挂载（供生命周期回调直接调用，幂等）
     */
    public static void attachIfMain(Activity activity) {
        if (activity instanceof MainActivity) {
            attach((MainActivity) activity);
        }
    }

    /**
     * 为 MainActivity 的 AI 菜单项挂载点击逻辑（幂等）
     */
    public static void attach(MainActivity activity) {
        try {
            View view = activity.findViewById(R.id.ai);
            if (!(view instanceof FCLMenuView)) {
                Logging.LOG.log(Level.WARNING, "[AI] AI 菜单视图未找到");
                return;
            }
            FCLMenuView menu = (FCLMenuView) view;
            if (Boolean.TRUE.equals(menu.getTag(R.id.ai))) {
                return;
            }
            menu.setTag(R.id.ai, Boolean.TRUE);

            // 与其它菜单完全一致：点击 → 选中（setSelected 状态变化会触发 OnSelectListener）
            menu.setOnClickListener(v -> menu.setSelected(true));
            menu.setOnSelectListener(v -> openAiPage(activity, v));

            Logging.LOG.info("[AI] AI 菜单已挂载");
        } catch (Throwable e) {
            Logging.LOG.log(Level.SEVERE, "[AI] AI 菜单挂载异常", e);
        }
    }

    /**
     * 打开 AI 控制器页面
     */
    private static void openAiPage(MainActivity activity, FCLMenuView menu) {
        try {
            activity.refreshMenuView(menu);
            UIManager ui = activity.getUiManager();
            ui.switchUI(ui.getAiUI());
            activity.getBinding().title.setTextWithAnim(
                    activity.getString(R.string.app_name) + " · AI");
        } catch (Throwable e) {
            Logging.LOG.log(Level.SEVERE, "[AI] 打开 AI 页面失败", e);
            try {
                Toast.makeText(activity, "打开AI页面失败: " + e, Toast.LENGTH_LONG).show();
            } catch (Throwable ignore) {
                // Toast 失败（如 Activity 已销毁）不影响主流程
            }
        }
    }
}
