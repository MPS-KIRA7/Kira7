package com.example.core

import android.content.Context
import android.graphics.Path
import android.graphics.RectF
import com.example.model.IconDefinition
import org.json.JSONObject

class IconLibrary private constructor(
    val icons: Map<String, IconDefinition>
) {

    fun resolve(noun: String?): IconDefinition {
        val clean = noun?.trim()?.lowercase()?.replace(Regex("[^a-z0-9_ ]"), "") ?: ""
        icons[clean]?.let { return it }

        // Check aliases and partial matches
        for (icon in icons.values) {
            if (icon.aliases.any { it.equals(clean, ignoreCase = true) }) {
                return icon
            }
        }
        for (icon in icons.values) {
            if (clean.contains(icon.name) || icon.aliases.any { clean.contains(it) }) {
                return icon
            }
        }
        return icons["lightbulb"] ?: icons.values.first()
    }

    fun extractUsedIcons(nouns: Collection<String>): Map<String, IconDefinition> {
        val map = linkedMapOf<String, IconDefinition>()
        for (noun in nouns) {
            val resolved = resolve(noun)
            map[resolved.name] = resolved
        }
        return map
    }

    companion object {
        fun loadFromAssets(context: Context): IconLibrary {
            val raw = context.assets.open("icons/library.json")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            return parseJson(raw)
        }

        fun parseJson(rawJson: String): IconLibrary {
            val root = JSONObject(rawJson)
            val iconsObj = root.getJSONObject("icons")
            val map = linkedMapOf<String, IconDefinition>()
            val keys = iconsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = iconsObj.getJSONObject(key)
                val aliasesArr = obj.optJSONArray("aliases")
                val aliases = buildList {
                    if (aliasesArr != null) {
                        for (i in 0 until aliasesArr.length()) {
                            add(aliasesArr.getString(i))
                        }
                    }
                }
                val pathsArr = obj.getJSONArray("paths")
                val paths = buildList {
                    for (i in 0 until pathsArr.length()) {
                        add(pathsArr.getString(i))
                    }
                }
                map[key] = IconDefinition(
                    name = key,
                    source = obj.optString("source", "hand_drawn"),
                    license = obj.optString("license", "CC0-1.0"),
                    aliases = aliases,
                    paths = paths
                )
            }
            return IconLibrary(map)
        }

        /**
         * Lightweight SVG path data parser (supports M, L, H, V, C, Q, A, Z commands in 0..24 viewBox)
         * converting directly to Android graphics Path without external dependencies.
         */
        fun svgToAndroidPath(d: String, scale: Float = 1f, offsetX: Float = 0f, offsetY: Float = 0f): Path {
            val path = Path()
            val tokens = tokenizeSvgPath(d)
            var idx = 0
            var cmd = 'M'
            var curX = 0f
            var curY = 0f
            var startX = 0f
            var startY = 0f

            fun nextFloat(): Float = tokens.getOrNull(idx++)?.toFloatOrNull() ?: 0f
            fun sx(x: Float) = offsetX + x * scale
            fun sy(y: Float) = offsetY + y * scale

            while (idx < tokens.size) {
                val token = tokens[idx]
                if (token.length == 1 && token[0].isLetter()) {
                    cmd = token[0]
                    idx++
                    if (cmd == 'Z' || cmd == 'z') {
                        path.close()
                        curX = startX
                        curY = startY
                        continue
                    }
                }
                when (cmd) {
                    'M' -> {
                        curX = nextFloat()
                        curY = nextFloat()
                        startX = curX
                        startY = curY
                        path.moveTo(sx(curX), sy(curY))
                        cmd = 'L'
                    }
                    'L' -> {
                        curX = nextFloat()
                        curY = nextFloat()
                        path.lineTo(sx(curX), sy(curY))
                    }
                    'H' -> {
                        curX = nextFloat()
                        path.lineTo(sx(curX), sy(curY))
                    }
                    'V' -> {
                        curY = nextFloat()
                        path.lineTo(sx(curX), sy(curY))
                    }
                    'C' -> {
                        val x1 = nextFloat(); val y1 = nextFloat()
                        val x2 = nextFloat(); val y2 = nextFloat()
                        val x = nextFloat(); val y = nextFloat()
                        path.cubicTo(sx(x1), sy(y1), sx(x2), sy(y2), sx(x), sy(y))
                        curX = x; curY = y
                    }
                    'Q' -> {
                        val x1 = nextFloat(); val y1 = nextFloat()
                        val x = nextFloat(); val y = nextFloat()
                        path.quadTo(sx(x1), sy(y1), sx(x), sy(y))
                        curX = x; curY = y
                    }
                    'A' -> {
                        val rx = nextFloat()
                        val ry = nextFloat()
                        nextFloat() // xAxisRotation
                        nextFloat() // largeArc
                        nextFloat() // sweep
                        val x = nextFloat()
                        val y = nextFloat()
                        val cx = (curX + x) / 2f
                        val cy = (curY + y) / 2f
                        val r = maxOf(rx, ry, 0.5f)
                        val oval = RectF(sx(cx - r), sy(cy - r), sx(cx + r), sy(cy + r))
                        path.addOval(oval, Path.Direction.CW)
                        curX = x
                        curY = y
                    }
                    else -> idx++
                }
            }
            return path
        }

        private fun tokenizeSvgPath(d: String): List<String> {
            val regex = Regex("[A-Za-z]|[-+]?(?:\\d*\\.\\d+|\\d+)")
            return regex.findAll(d).map { it.value }.toList()
        }
    }
}
