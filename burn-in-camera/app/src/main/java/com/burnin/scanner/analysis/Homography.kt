package com.burnin.scanner.analysis

/**
 * 3x3 호모그래피 (화면 좌표 → 카메라 이미지 좌표).
 * 4점 대응으로 DLT 8x8 선형계를 풀어 구한다 (M-FR-007).
 */
class Homography private constructor(private val m: DoubleArray) {

    /** (u,v) 화면 좌표 → 이미지 좌표 (out[0]=x, out[1]=y) */
    fun map(u: Double, v: Double, out: DoubleArray) {
        require(out.size >= 2 && u.isFinite() && v.isFinite()) { "invalid projection input" }
        val w = m[6] * u + m[7] * v + 1.0
        require(w.isFinite() && Math.abs(w) > 1e-12) { "projection is at infinity" }
        out[0] = (m[0] * u + m[1] * v + m[2]) / w
        out[1] = (m[3] * u + m[4] * v + m[5]) / w
    }

    companion object {
        /**
         * src(화면) 4점 → dst(이미지) 4점 대응으로 호모그래피 계산.
         * 순서는 서로 대응해야 한다 (예: 둘 다 TL,TR,BR,BL).
         */
        fun from4Points(src: Array<Vec2>, dst: Array<Vec2>): Homography? {
            require(src.size == 4 && dst.size == 4)
            if ((src.asList() + dst.asList()).any { !it.x.isFinite() || !it.y.isFinite() }) return null
            val a = Array(8) { DoubleArray(9) }
            for (k in 0 until 4) {
                fillRows(a, k * 2, src[k], dst[k])
            }
            val h = solve(a) ?: return null
            return Homography(h)
        }

        /**
         * 4개를 초과하는 대응점으로 최소제곱 호모그래피를 계산한다.
         * dot-grid/fiducial 패턴처럼 내부 기준점이 여럿 있을 때 4모서리 해법보다 안정적이다.
         */
        fun fromPointPairs(src: List<Vec2>, dst: List<Vec2>): Homography? {
            require(src.size == dst.size) { "point-pair size mismatch" }
            require(src.size >= 4) { "at least four point pairs required" }
            if ((src + dst).any { !it.x.isFinite() || !it.y.isFinite() }) return null
            val normal = Array(8) { DoubleArray(9) }
            val rows = Array(2) { DoubleArray(9) }
            for (i in src.indices) {
                fillRows(rows, 0, src[i], dst[i])
                accumulateNormal(normal, rows[0])
                accumulateNormal(normal, rows[1])
            }
            val h = solve(normal) ?: return null
            return Homography(h)
        }

        private fun fillRows(rows: Array<DoubleArray>, offset: Int, src: Vec2, dst: Vec2) {
            val u = src.x.toDouble()
            val v = src.y.toDouble()
            val x = dst.x.toDouble()
            val y = dst.y.toDouble()
            rows[offset] = doubleArrayOf(u, v, 1.0, 0.0, 0.0, 0.0, -x * u, -x * v, x)
            rows[offset + 1] = doubleArrayOf(0.0, 0.0, 0.0, u, v, 1.0, -y * u, -y * v, y)
        }

        private fun accumulateNormal(normal: Array<DoubleArray>, row: DoubleArray) {
            for (r in 0 until 8) {
                for (c in 0 until 8) {
                    normal[r][c] += row[r] * row[c]
                }
                normal[r][8] += row[r] * row[8]
            }
        }

        /** 부분 피벗 가우스 소거로 8x8 선형계 풀기. 특이행렬이면 null. */
        private fun solve(aug: Array<DoubleArray>): DoubleArray? {
            val n = 8
            for (col in 0 until n) {
                var pivot = col
                for (r in col + 1 until n) {
                    if (Math.abs(aug[r][col]) > Math.abs(aug[pivot][col])) pivot = r
                }
                if (Math.abs(aug[pivot][col]) < 1e-12) return null
                val tmp = aug[col]; aug[col] = aug[pivot]; aug[pivot] = tmp
                val p = aug[col][col]
                for (c in col..n) aug[col][c] /= p
                for (r in 0 until n) {
                    if (r == col) continue
                    val f = aug[r][col]
                    if (f == 0.0) continue
                    for (c in col..n) aug[r][c] -= f * aug[col][c]
                }
            }
            return DoubleArray(8) { aug[it][8] }.takeIf { values -> values.all { it.isFinite() } }
        }
    }
}
