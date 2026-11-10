package com.ofer.ideanotes

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * סגנון "קריקטורה מאוזנת": קווי מתאר עבים, צל קשיח (בלי טשטוש) ופלטה קבועה.
 * רק צורות וצבעים, בלי אנימציות, כך שאין השפעה על הביצועים.
 */
object Ui {
    val INK = Color.parseColor("#302C38")
    val CREAM = Color.parseColor("#FFF8E7")
    val WHITE = Color.WHITE
    val SKY_L = Color.parseColor("#D6E9FB")
    val MINT = Color.parseColor("#5FD6A0")
    val MINT_L = Color.parseColor("#D3F4E5")
    val CORAL = Color.parseColor("#F5877F")
    val SKY = Color.parseColor("#7DBCF4")
    val SUN = Color.parseColor("#FFD45C")

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    /** מלבן מעוגל עם מסגרת כהה, ואם shadowDp > 0 גם צל קשיח לימין-למטה. */
    fun box(c: Context, fill: Int, radiusDp: Int, shadowDp: Int = 0, shadowColor: Int = INK): Drawable {
        fun gd(color: Int, stroke: Boolean) = GradientDrawable().apply {
            cornerRadius = dp(c, radiusDp).toFloat()
            setColor(color)
            if (stroke) setStroke(dp(c, 3), INK)
        }
        val front = gd(fill, true)
        if (shadowDp <= 0) return front
        val sh = dp(c, shadowDp)
        return LayerDrawable(arrayOf<Drawable>(gd(shadowColor, false), front)).apply {
            setLayerInset(0, sh, sh, 0, 0)
            setLayerInset(1, 0, 0, sh, sh)
        }
    }

    private fun base(v: TextView) {
        v.setTextColor(INK)
        v.isAllCaps = false
        v.gravity = Gravity.CENTER
        if (v is Button) v.stateListAnimator = null
    }

    /** כפתור ראשי: צבע מלא וצל. */
    fun button(v: TextView, fill: Int, textSp: Float = 16f, heightDp: Int = 54, shadowDp: Int = 4) {
        val c = v.context
        base(v)
        v.textSize = textSp
        v.typeface = Typeface.DEFAULT_BOLD
        v.background = box(c, fill, 16, shadowDp)
        v.maxLines = 1
        v.minHeight = dp(c, heightDp)
        v.minimumHeight = dp(c, heightDp)
        v.setPadding(dp(c, 8), dp(c, 4), dp(c, 8) + dp(c, shadowDp), dp(c, 4) + dp(c, shadowDp))
    }

    /** תג סוג הערה: נבחר = ירוק מלא עם צל, אחרת תכלת בהיר. */
    fun chip(v: TextView, selected: Boolean, padH: Int = 8, padV: Int = 8) {
        val c = v.context
        base(v)
        v.minWidth = 0
        v.minimumWidth = 0
        v.minHeight = 0
        v.minimumHeight = 0
        v.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        // אותו גודל לכולם: לתג שלא נבחר יש מקום לצל, אבל הצל שקוף
        val sh = 3
        v.maxLines = 1
        v.background = box(c, if (selected) MINT else SKY_L, 40, sh, if (selected) INK else Color.TRANSPARENT)
        v.setPadding(dp(c, padH), dp(c, padV), dp(c, padH) + dp(c, sh), dp(c, padV) + dp(c, sh))
    }

    /** כרטיס (שדה כתיבה, תצוגה מקדימה). */
    fun card(v: View, fill: Int, radiusDp: Int, shadowDp: Int, pad: Int = 14) {
        val c = v.context
        v.background = box(c, fill, radiusDp, shadowDp)
        v.setPadding(dp(c, pad), dp(c, pad), dp(c, pad) + dp(c, shadowDp), dp(c, pad) + dp(c, shadowDp))
    }
}
