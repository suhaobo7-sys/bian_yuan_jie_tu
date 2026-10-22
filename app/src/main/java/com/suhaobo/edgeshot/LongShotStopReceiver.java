package com.suhaobo.edgeshot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 通知里的「停止 / 完成」：点一下让长截图收工 */
public class LongShotStopReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        LongShotService.requestStopFromNotification();
    }
}
