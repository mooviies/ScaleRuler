package com.mooviies.scaleruler

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

object SettingsManager {
    private val propsFile: File by lazy {
        val home = System.getProperty("user.home") ?: "."
        File(home, ".scaleruler.properties")
    }

    private fun load(): Properties {
        val p = Properties()
        if (propsFile.exists()) {
            runCatching { FileInputStream(propsFile).use { p.load(it) } }
        }
        return p
    }

    private fun save(p: Properties) {
        runCatching {
            // ensure parent exists
            propsFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            FileOutputStream(propsFile).use { out ->
                p.store(out, "ScaleRuler settings")
            }
        }
    }

    fun getLastPath(): String? {
        val p = load()
        return p.getProperty("lastPath")
    }

    fun setLastPath(path: String) {
        val p = load()
        p.setProperty("lastPath", path)
        save(p)
    }

    fun getScale(path: String): Double? {
        val p = load()
        val key = "scale." + path
        val v = p.getProperty(key) ?: return null
        return v.toDoubleOrNull()
    }

    fun setScale(path: String, inchesPerPixel: Double) {
        val p = load()
        val key = "scale." + path
        p.setProperty(key, inchesPerPixel.toString())
        save(p)
    }

    fun clearScale(path: String) {
        val p = load()
        val key = "scale." + path
        if (p.containsKey(key)) {
            p.remove(key)
            save(p)
        }
    }

    // Measurements persistence: store normalized coordinates per image
    // Format: entries separated by '|', each entry: x1,y1,x2,y2 using Locale.US decimals
    fun getMeasurements(path: String): List<DoubleArray> {
        val p = load()
        val key = "meas." + path
        val raw = p.getProperty(key) ?: return emptyList()
        val list = mutableListOf<DoubleArray>()
        raw.split('|').forEach { entry ->
            if (entry.isBlank()) return@forEach
            val parts = entry.split(',')
            if (parts.size == 4) {
                val nums = parts.mapNotNull { it.toDoubleOrNull() }
                if (nums.size == 4) {
                    list.add(doubleArrayOf(nums[0], nums[1], nums[2], nums[3]))
                }
            }
        }
        return list
    }

    fun setMeasurements(path: String, measurements: List<DoubleArray>) {
        val p = load()
        val key = "meas." + path
        val value = measurements.joinToString("|") { arr ->
            // Ensure 4 values
            val x1 = arr.getOrNull(0) ?: 0.0
            val y1 = arr.getOrNull(1) ?: 0.0
            val x2 = arr.getOrNull(2) ?: 0.0
            val y2 = arr.getOrNull(3) ?: 0.0
            String.format(java.util.Locale.US, "%f,%f,%f,%f", x1, y1, x2, y2)
        }
        p.setProperty(key, value)
        save(p)
    }
}
