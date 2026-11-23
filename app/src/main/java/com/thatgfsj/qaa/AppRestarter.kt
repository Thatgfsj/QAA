package com.thatgfsj.qaa

import android.app.Activity
import android.content.Intent

/** 立即重启契约（Issue #7）：模式切换后重建任务栈使新 ui_mode 生效。 */
interface AppRestarter {
    fun restart()
}

/** 基于当前 Activity 的实现：launch intent 重建任务栈并结束现有全部实例。 */
class ActivityAppRestarter(private val activity: Activity) : AppRestarter {

    override fun restart() {
        val launchIntent =
            activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(launchIntent)
        activity.finishAffinity()
    }
}
