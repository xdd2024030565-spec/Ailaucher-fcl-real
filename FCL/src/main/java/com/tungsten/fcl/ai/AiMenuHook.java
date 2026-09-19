package com.tungsten.fcl.ai;

import android.view.View;

import com.tungsten.fcl.R;
import com.tungsten.fcl.activity.MainActivity;
import com.tungsten.fcl.ui.UIManager;
import com.tungsten.fclcore.util.Logging;
import com.tungsten.fcllibrary.component.view.FCLMenuView;

import java.util.logging.Level;

/**
 * AI 菜单钩子 —— 无侵入接入 FCL 主界面菜单
 *
 * 由 FCLApp.onActivityCreated 调用，为左侧菜单的 ai 项挂载点击逻辑。
 * 采用生命周期回调 + findViewById 方式，避免修改 MainActivity.kt 大量代码。
 */
public final class AiMenuHook {

    private AiMenuHook() {
    }

    /**
     * 为 MainActivity 的 AI 菜单项挂载点击逻辑（幂等）
     */
    public static void attach(MainActivity activity) {
        try {
            View view = activity.findViewById(R.id.ai);
            if (!(view instanceof FCLMenuView)) {
                return;
            }
            FCLMenuView menu = (FCLMenuView) view;
            if (menu.getTag(R.id.ai) != null) {
                return;
            }
            menu.setTag(R.id.ai, Boolean.TRUE);
            menu.setOnClickListener(v -> openAiPage(activity, menu));
        } catch (Exception e) {
            Logging.LOG.log(Level.WARNING, "AI 菜单挂载失败", e);
        }
    }

    /**
     * 打开 AI 控制器页面：同步菜单高亮、切换页面与标题
     */
    private static void openAiPage(MainActivity activity, FCLMenuView menu) {
        try {
            activity.refreshMenuView(menu);
            menu.setSelected(true);

            UIManager ui = activity.getUiManager();
            ui.switchUI(ui.getAiUI());

            activity.getBinding().title.setTextWithAnim(
                    activity.getString(R.string.app_name) + " · AI");
        } catch (Exception e) {
            Logging.LOG.log(Level.WARNING, "无法打开 AI 页面", e);
        }
    }
}
