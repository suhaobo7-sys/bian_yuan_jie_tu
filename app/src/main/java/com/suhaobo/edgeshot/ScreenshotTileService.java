package com.suhaobo.edgeshot;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

/**
 * 快捷设置里的「截图」磁贴：下拉通知栏点一下就进截图。
 * 真正的活儿在 {@link ShotActivity}（透明界面）里干。
 */
public class ScreenshotTileService extends TileService {

    @Override
    public void onClick() {
        super.onClick();

        // 磁贴是从「展开的通知栏」点进来的：先让无障碍服务把通知栏收起来。
        // 光靠 startActivityAndCollapse 在 MIUI/HyperOS 上不一定收得掉，而通知栏不收掉的话
        // 会被一起截进图里（用户看到的截图就是一片通知栏）。
        ShotAccessibilityService svc = ShotAccessibilityService.get();
        if (svc != null) svc.collapseNotificationShade();

        Intent intent = new Intent(this, ShotActivity.class);
        intent.putExtra(ShotActivity.EXTRA_FROM_TILE, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14 起用 PendingIntent 版本（旧的在 14+ 已被限制）
                PendingIntent pi = PendingIntent.getActivity(this, 1, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pi);
            } else {
                startActivityAndCollapse(intent);
            }
        } catch (Exception e) {
            try {
                startActivity(intent);
            } catch (Exception ignored) { /* 起不来就算了 */ }
        }
    }
}
