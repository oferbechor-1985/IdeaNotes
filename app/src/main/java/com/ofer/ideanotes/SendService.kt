package com.ofer.ideanotes

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** לוחץ על כפתור "שלח" בווטסאפ, רק כשהאפליקציה חימשה אותו הרגע (ראו AutoSend). */
class SendService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!AutoSend.armed()) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        val send = findSend(root, pkg) ?: return
        if (send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) AutoSend.disarm()
    }

    private fun findSend(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        // הכפתור "שלח" מופיע רק כשיש טקסט בתיבה (אחרת מוצג כפתור ההקלטה)
        root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull { it.isEnabled }?.let { return it }
        for (label in listOf("שלח", "Send")) {
            root.findAccessibilityNodeInfosByText(label)
                .firstOrNull { it.isClickable && it.contentDescription?.toString() == label }
                ?.let { return it }
        }
        return null
    }

    override fun onInterrupt() {}
}
