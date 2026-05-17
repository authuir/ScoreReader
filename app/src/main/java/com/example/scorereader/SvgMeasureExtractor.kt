package com.example.scorereader

import android.graphics.RectF

/**
 * Tiny SVG scanner that pulls every `<g class="measure" id="…">` block
 * out of a Verovio-rendered SVG and computes its bounding box in
 * *output bitmap* pixel coordinates.
 *
 * Bounding boxes are derived from the staff-line `<path>` elements inside
 * the measure group. Every Verovio measure contains at least one
 * `<g class="staff">` with horizontal `<path class="staffLine" d="M x1 y1 L x2 y2"/>`
 * elements; taking the union of those gives the measure's playable area.
 *
 * This is deliberately permissive — we tolerate floats, different
 * attribute orderings, and missing elements; measures we can't bound
 * are simply omitted so playback still works without highlight.
 */
internal object SvgMeasureExtractor {

    data class MeasureBox(val id: String, val bbox: RectF)

    /** Output of [extract]: the SVG viewBox plus measures whose [MeasureBox.bbox]
     *  is expressed in **viewBox** coordinates (not bitmap pixels). The
     *  overlay applies its own xMidYMid-meet transform at draw time using
     *  these so highlights always line up with whatever size the score
     *  image is actually drawn at on screen (which may differ from the
     *  rendered bitmap when fitCenter letterboxes). */
    data class ExtractResult(val viewBox: RectF, val measures: List<MeasureBox>)

    /**
     * Extract every measure from [svg] and return its bbox in *root* SVG
     * viewBox coordinates along with that viewBox. The overlay does the
     * viewBox → screen transform itself.
     */
    fun extract(svg: String): ExtractResult {
        val rootVb = parseFirstViewBox(svg) ?: return ExtractResult(RectF(), emptyList())
        val (rootX, rootY, rootW, rootH) = rootVb
        val viewBox = RectF(rootX, rootY, rootX + rootW, rootY + rootH)
        if (rootW <= 0f || rootH <= 0f) return ExtractResult(viewBox, emptyList())

        // Verovio nests its content inside another <svg> whose own viewBox is
        // the "vrv internal" coordinate space:
        //   <svg viewBox="0 0 rootW rootH">
        //     <svg class="definition-scale" viewBox="0 0 innerW innerH">
        //       <g class="page-margin" transform="translate(ox,oy)"> ...
        // staffLine `d` values are in the page-margin's local frame, so the
        // mapping to root-viewBox coords is:
        //   outX = (innerX + ox) * scale + offX
        //   scale, offX, offY = xMidYMid-meet from inner viewBox to root viewBox.
        val innerVb = parseDefinitionScaleViewBox(svg) ?: rootVb
        val innerW = innerVb[2]
        val innerH = innerVb[3]
        val (ox, oy) = parsePageMarginTranslate(svg)

        val scale: Float
        val offX: Float
        val offY: Float
        if (innerW > 0f && innerH > 0f) {
            scale = minOf(rootW / innerW, rootH / innerH)
            offX = (rootW - innerW * scale) / 2f + rootX
            offY = (rootH - innerH * scale) / 2f + rootY
        } else {
            scale = 1f; offX = rootX; offY = rootY
        }

        val out = ArrayList<MeasureBox>()
        var cursor = 0
        while (true) {
            val (_, openEnd, id) = findNextMeasureOpenTag(svg, cursor) ?: break
            val closeIdx = findMatchingClose(svg, openEnd)
            cursor = if (closeIdx >= 0) closeIdx else openEnd
            if (closeIdx < 0) continue
            val slice = svg.substring(openEnd, closeIdx)
            val bboxRaw = computeStaffLineBbox(slice) ?: computeAnyPathBbox(slice) ?: continue
            val left   = (bboxRaw.left   + ox) * scale + offX
            val top    = (bboxRaw.top    + oy) * scale + offY
            val right  = (bboxRaw.right  + ox) * scale + offX
            val bottom = (bboxRaw.bottom + oy) * scale + offY
            out.add(MeasureBox(id, RectF(left, top, right, bottom)))
        }
        return ExtractResult(viewBox, out)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** First `<svg … viewBox="x y w h">` in the document (== root). */
    private fun parseFirstViewBox(svg: String): FloatArray? {
        val m = VIEWBOX.find(svg) ?: return null
        return parseViewBoxAttr(m.groupValues[1])
    }

    /** The viewBox of the inner `<svg class="definition-scale">` element,
     *  if present. Returns null if not found. */
    private fun parseDefinitionScaleViewBox(svg: String): FloatArray? {
        val m = DEFINITION_SCALE_SVG.find(svg) ?: return null
        // Look for viewBox inside the opening tag we matched.
        val tag = m.value
        val vb = VIEWBOX_ATTR.find(tag) ?: return null
        return parseViewBoxAttr(vb.groupValues[1])
    }

    /** The (ox, oy) from the `<g class="page-margin" transform="translate(ox,oy)">`. */
    private fun parsePageMarginTranslate(svg: String): Pair<Float, Float> {
        val m = PAGE_MARGIN.find(svg) ?: return 0f to 0f
        val transform = m.groupValues[1]
        val t = TRANSLATE_VAL.find(transform) ?: return 0f to 0f
        val tx = t.groupValues[1].toFloatOrNull() ?: 0f
        val ty = t.groupValues[2].toFloatOrNull() ?: 0f
        return tx to ty
    }

    private fun parseViewBoxAttr(value: String): FloatArray? {
        val parts = value.trim().split(WS_OR_COMMA)
        if (parts.size < 4) return null
        return try {
            floatArrayOf(parts[0].toFloat(), parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
        } catch (_: NumberFormatException) { null }
    }

    private data class OpenTag(val openStart: Int, val openEnd: Int, val id: String)

    private fun findNextMeasureOpenTag(svg: String, from: Int): OpenTag? {
        var i = from
        while (true) {
            val open = svg.indexOf("<g", i)
            if (open < 0) return null
            val gtag = svg.indexOf('>', open)
            if (gtag < 0) return null
            val tagText = svg.substring(open, gtag + 1)
            i = gtag + 1
            if (!tagText.contains("class=\"") ) continue
            // Only match groups that are *exactly* the measure class
            // (other classes like "measureNumber" must not match).
            if (!HAS_MEASURE_CLASS.containsMatchIn(tagText)) continue
            val idMatch = HAS_ID.find(tagText) ?: continue
            return OpenTag(open, gtag + 1, idMatch.groupValues[1])
        }
    }

    /** Walks balanced `<g …>` opens / `</g>` closes starting from
     *  [from] (immediately after a `<g …>` open tag) and returns the index
     *  of its matching `</g>`. Returns -1 if not found. */
    private fun findMatchingClose(svg: String, from: Int): Int {
        var depth = 1
        var i = from
        while (i < svg.length) {
            val nextOpen = svg.indexOf("<g", i)
            val nextClose = svg.indexOf("</g>", i)
            if (nextClose < 0) return -1
            if (nextOpen in 0 until nextClose) {
                // Skip self-closing `<g …/>` (rare but possible).
                val gtag = svg.indexOf('>', nextOpen)
                if (gtag < 0) return -1
                val tagText = svg.substring(nextOpen, gtag + 1)
                if (!tagText.endsWith("/>")) depth++
                i = gtag + 1
            } else {
                depth--
                if (depth == 0) return nextClose
                i = nextClose + 4
            }
        }
        return -1
    }

    private fun computeStaffLineBbox(slice: String): RectF? {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var found = false
        STAFFLINE.findAll(slice).forEach { m ->
            val x1 = m.groupValues[1].toFloatOrNull() ?: return@forEach
            val y1 = m.groupValues[2].toFloatOrNull() ?: return@forEach
            val x2 = m.groupValues[3].toFloatOrNull() ?: return@forEach
            val y2 = m.groupValues[4].toFloatOrNull() ?: return@forEach
            if (x1 < minX) minX = x1; if (x2 < minX) minX = x2
            if (x1 > maxX) maxX = x1; if (x2 > maxX) maxX = x2
            if (y1 < minY) minY = y1; if (y2 < minY) minY = y2
            if (y1 > maxY) maxY = y1; if (y2 > maxY) maxY = y2
            found = true
        }
        if (!found) return null
        // Pad a touch vertically so the highlight visibly clears the staff
        // lines top/bottom.
        val padY = (maxY - minY) * 0.10f
        return RectF(minX, minY - padY, maxX, maxY + padY)
    }

    /** Last-resort bbox: scan any `M x y` / `L x y` pairs in any path's d
     *  attribute. */
    private fun computeAnyPathBbox(slice: String): RectF? {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var found = false
        ML_PAIR.findAll(slice).forEach { m ->
            val x = m.groupValues[1].toFloatOrNull() ?: return@forEach
            val y = m.groupValues[2].toFloatOrNull() ?: return@forEach
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            found = true
        }
        if (!found) return null
        return RectF(minX, minY, maxX, maxY)
    }

    private val VIEWBOX = Regex("""<svg\b[^>]*\bviewBox="([^"]+)"""")
    private val VIEWBOX_ATTR = Regex("""\bviewBox="([^"]+)"""")
    private val HAS_MEASURE_CLASS = Regex("""\bclass="(?:[^"]*\s)?measure(?:\s[^"]*)?"""")
    private val HAS_ID = Regex("""\bid="([^"]+)"""")
    private val STAFFLINE = Regex(
        """<path\b[^>]*\bclass="(?:[^"]*\s)?staffLine(?:\s[^"]*)?"[^>]*\bd="M\s*(-?[\d.]+)[ ,]+(-?[\d.]+)\s*L\s*(-?[\d.]+)[ ,]+(-?[\d.]+)"""",
        RegexOption.IGNORE_CASE
    )
    private val ML_PAIR = Regex("""[ML]\s*(-?[\d.]+)[ ,]+(-?[\d.]+)""")
    private val WS_OR_COMMA = Regex("[\\s,]+")

    // Verovio's inner <svg class="definition-scale" viewBox="..."> opening
    // tag. We capture the whole opening tag and pull viewBox out separately.
    private val DEFINITION_SCALE_SVG = Regex(
        """<svg\b[^>]*\bclass="(?:[^"]*\s)?definition-scale(?:\s[^"]*)?"[^>]*>"""
    )
    private val PAGE_MARGIN = Regex(
        """<g\b[^>]*\bclass="(?:[^"]*\s)?page-margin(?:\s[^"]*)?"[^>]*\btransform="([^"]+)""""
    )
    private val TRANSLATE_VAL = Regex("""\btranslate\(\s*(-?[\d.]+)[\s,]+(-?[\d.]+)""")
}
