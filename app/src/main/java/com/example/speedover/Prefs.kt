// Prefs.kt
package com.example.speedover

import android.content.Context
import android.graphics.Color

/**
 * SpeedOver Safety Awareness
 * Holle TechNolle, 2026
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

    var gpsKeepaliveSeconds:       Int get() = sp.getInt("gps_keepalive",        30); set(v) = sp.edit().putInt("gps_keepalive",        v).apply()
    var speedLimitIntervalSeconds: Int get() = sp.getInt("speed_limit_interval", 10); set(v) = sp.edit().putInt("speed_limit_interval", v).apply()
    var overpassRadiusMeters:      Int get() = sp.getInt("overpass_radius",      50); set(v) = sp.edit().putInt("overpass_radius",      v).apply()

    // Road info display toggles
    var showRoadName: Boolean get() = sp.getBoolean("show_road_name", true); set(v) = sp.edit().putBoolean("show_road_name", v).apply()
    var showRoadType: Boolean get() = sp.getBoolean("show_road_type", true); set(v) = sp.edit().putBoolean("show_road_type", v).apply()

    // Fallback speed limits per OSM highway type — Danish defaults
    // 0 = disabled (no speed limit shown for this road type)
    var limitMotorway:     Int get() = sp.getInt("lim_motorway",      130); set(v) = sp.edit().putInt("lim_motorway",      v).apply()
    var limitMotorwayLink: Int get() = sp.getInt("lim_motorway_link", 130); set(v) = sp.edit().putInt("lim_motorway_link", v).apply()
    var limitTrunk:        Int get() = sp.getInt("lim_trunk",          80); set(v) = sp.edit().putInt("lim_trunk",          v).apply()
    var limitTrunkLink:    Int get() = sp.getInt("lim_trunk_link",     80); set(v) = sp.edit().putInt("lim_trunk_link",     v).apply()
    var limitPrimary:      Int get() = sp.getInt("lim_primary",        80); set(v) = sp.edit().putInt("lim_primary",        v).apply()
    var limitSecondary:    Int get() = sp.getInt("lim_secondary",      80); set(v) = sp.edit().putInt("lim_secondary",      v).apply()
    var limitTertiary:     Int get() = sp.getInt("lim_tertiary",       50); set(v) = sp.edit().putInt("lim_tertiary",       v).apply()
    var limitUnclassified: Int get() = sp.getInt("lim_unclassified",   80); set(v) = sp.edit().putInt("lim_unclassified",   v).apply()
    var limitResidential:  Int get() = sp.getInt("lim_residential",    50); set(v) = sp.edit().putInt("lim_residential",    v).apply()
    var limitLivingStreet: Int get() = sp.getInt("lim_living_street",  15); set(v) = sp.edit().putInt("lim_living_street",  v).apply()
}