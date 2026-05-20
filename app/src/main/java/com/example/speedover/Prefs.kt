package com.example.speedover

import android.content.Context
import android.graphics.Color

/**
 * Transpeedo — GPS Speedometer Overlay
 * Holle TechNolle, 2025
 *
 * Shared preferences wrapper.
 * Personal use only. Untested. Built for Xiaomi T10 — may work on other devices.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("speedover", Context.MODE_PRIVATE)

    var fillColor:    Int   get() = sp.getInt("fill_color",    Color.WHITE); set(v) = sp.edit().putInt("fill_color",    v).apply()
    var strokeColor:  Int   get() = sp.getInt("stroke_color",  Color.BLACK); set(v) = sp.edit().putInt("stroke_color",  v).apply()
    var textAlpha:    Int   get() = sp.getInt("text_alpha",    255);          set(v) = sp.edit().putInt("text_alpha",    v).apply()
    var strokeWidth:  Float get() = sp.getFloat("stroke_width", 3f);          set(v) = sp.edit().putFloat("stroke_width", v).apply()
    var textSizePx:   Float get() = sp.getFloat("text_size",   200f);         set(v) = sp.edit().putFloat("text_size",   v).apply()
    var overlayX:     Int   get() = sp.getInt("overlay_x",    100);           set(v) = sp.edit().putInt("overlay_x",    v).apply()
    var overlayY:     Int   get() = sp.getInt("overlay_y",    100);           set(v) = sp.edit().putInt("overlay_y",    v).apply()
    var overlayWidth: Int   get() = sp.getInt("overlay_w",    480);           set(v) = sp.edit().putInt("overlay_w",    v).apply()
    var overlayHeight:Int   get() = sp.getInt("overlay_h",    280);           set(v) = sp.edit().putInt("overlay_h",    v).apply()
    var fontName:     String get() = sp.getString("font_name", "segoe") ?: "segoe"; set(v) = sp.edit().putString("font_name", v).apply()
    var gpsKeepaliveSeconds: Int get() = sp.getInt("gps_keepalive", 30); set(v) = sp.edit().putInt("gps_keepalive", v).apply()
}