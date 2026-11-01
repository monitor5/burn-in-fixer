package com.burnin.target.pattern

/**
 * 테스트 패턴 정의. 이름 규칙:
 *  - "black", "white70", "white85", "gray25", "gray70", "red70", "green70", "blue70" : 단색
 *  - "marker"   : 좌표 정합용 마커 (모서리 사각형 + 방향 마커 + 중앙 십자)
 *  - "grid"     : 격자
 *  - "checker"  : 체커보드 (렌즈 왜곡 캘리브레이션용)
 *  - "dotgrid"  : 도트 그리드 (렌즈 왜곡 캘리브레이션용)
 *  - "sparse70_b1_p4_x0_y3" : 희소 점등. block×block 픽셀 블록만 pitch 간격으로 켠다(밝기 70%).
 *  - "sparse70_b1_p4_x0_y3_cr" : 같은 배치를 한 채널(r/g/b)만 켠다(서브픽셀별 측정).
 *    블록 좌상단은 (x·block + i·pitch, y·block + j·pitch). 글씨 획 단위 번인 측광용.
 *  - "rgb-RRGGBB" : 임의 코드값 단색 (색감 측정 패치, 16진수)
 */
data class PatternSpec(
    val name: String,
    val kind: Kind,
    val color: Int = COLOR_BLACK,
    val cells: Int = 8,
    val block: Int = 1,
    val pitch: Int = 1,
    val phaseX: Int = 0,
    val phaseY: Int = 0,
) {
    enum class Kind { SOLID, MARKER, GRID, CHECKER, DOTGRID, SPARSE }

    /** 희소 패턴에서 화면 픽셀 (x, y)가 켜지는지 */
    fun sparseLit(x: Int, y: Int): Boolean {
        val bx = x - phaseX * block
        val by = y - phaseY * block
        return bx >= 0 && by >= 0 && bx % pitch < block && by % pitch < block
    }

    companion object {
        const val COLOR_BLACK = 0xFF000000.toInt()

        private fun rgb(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b

        fun solid(name: String, color: Int) = PatternSpec(name, Kind.SOLID, color)

        val BLACK = solid("black", COLOR_BLACK)

        private val solidRegex = Regex("^(black|white|gray|grey|red|green|blue)(\\d{1,3})?$")
        private val sparseRegex = Regex("^sparse(\\d{1,3})_b(\\d)_p(\\d{1,2})_x(\\d{1,2})_y(\\d{1,2})(?:_c([rgb]))?$")

        private fun parseSparse(name: String): PatternSpec? {
            val m = sparseRegex.matchEntire(name) ?: return null
            val (pctText, blockText, pitchText, xText, yText, channelText) = m.destructured
            val pct = pctText.toInt().coerceIn(0, 100)
            val block = blockText.toInt()
            val pitch = pitchText.toInt()
            val px = xText.toInt()
            val py = yText.toInt()
            if (block !in 1..4 || pitch < 2 * block || pitch % block != 0) return null
            val phases = pitch / block
            if (px >= phases || py >= phases) return null
            val v = Math.round(255f * pct / 100f)
            val color = when (channelText) {
                "r" -> rgb(v, 0, 0)
                "g" -> rgb(0, v, 0)
                "b" -> rgb(0, 0, v)
                else -> rgb(v, v, v)
            }
            return PatternSpec(name, Kind.SPARSE, color, block = block, pitch = pitch, phaseX = px, phaseY = py)
        }

        private val rgbRegex = Regex("^rgb-([0-9a-f]{6})$")

        /** 패턴 이름 문자열을 해석한다. 알 수 없는 이름이면 null. */
        fun parse(raw: String): PatternSpec? {
            val name = raw.trim().lowercase()
            rgbRegex.matchEntire(name)?.let { m ->
                return solid(name, (0xFF shl 24) or m.groupValues[1].toInt(16))
            }
            solidRegex.matchEntire(name)?.let { m ->
                val base = m.groupValues[1]
                val pct = (m.groupValues[2].toIntOrNull() ?: 100).coerceIn(0, 100)
                val v = Math.round(255f * pct / 100f)
                val color = when (base) {
                    "black" -> COLOR_BLACK
                    "white", "gray", "grey" -> rgb(v, v, v)
                    "red" -> rgb(v, 0, 0)
                    "green" -> rgb(0, v, 0)
                    "blue" -> rgb(0, 0, v)
                    else -> return null
                }
                return solid(name, if (base == "black") COLOR_BLACK else color)
            }
            parseSparse(name)?.let { return it }
            return when (name) {
                "marker" -> PatternSpec(name, Kind.MARKER)
                "grid" -> PatternSpec(name, Kind.GRID, cells = 12)
                "checker", "checkerboard" -> PatternSpec(name, Kind.CHECKER, cells = 10)
                "dotgrid", "dots" -> PatternSpec(name, Kind.DOTGRID, cells = 12)
                else -> null
            }
        }
    }
}
