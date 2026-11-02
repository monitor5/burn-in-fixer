package com.burnin.scanner.camera

import java.io.*

internal object CaptureFrameCodec {
    private const val MAGIC = 0x42494631
    fun write(file: File, frame: CaptureFrame) {
        DataOutputStream(BufferedOutputStream(file.outputStream())).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(frame.format)
            out.writeInt(frame.width)
            out.writeInt(frame.height)
            out.writeLong(frame.timestampNs)
            out.writeBoolean(frame.sensitivityIso != null)
            frame.sensitivityIso?.let { out.writeInt(it) }
            out.writeBoolean(frame.exposureTimeNs != null)
            frame.exposureTimeNs?.let { out.writeLong(it) }
            out.writeInt(frame.planes.size)
            for (plane in frame.planes) {
                out.writeInt(plane.rowStride)
                out.writeInt(plane.pixelStride)
                out.writeInt(plane.bytes.size)
                out.write(plane.bytes)
            }
            writeRaw(out, frame.raw)
        }
    }

    private fun writeRaw(out: DataOutputStream, raw: CaptureFrame.RawInfo?) {
        out.writeBoolean(raw != null)
        if (raw != null) {
            out.writeInt(raw.cfa)
            raw.blackLevel.forEach { out.writeInt(it) }
            out.writeInt(raw.whiteLevel)
            out.writeFloat(raw.gainR); out.writeFloat(raw.gainG); out.writeFloat(raw.gainB)
            out.writeBoolean(raw.ccm != null)
            raw.ccm?.forEach { out.writeFloat(it) }
            val sh = raw.shading
            out.writeBoolean(sh != null)
            if (sh != null) {
                out.writeInt(sh.cols); out.writeInt(sh.rows)
                sh.gains.forEach { out.writeFloat(it) }
            }
        }
    }

    /** [write]와 같은 형식으로, 평면 데이터를 Java 배열로 복사하지 않고 카메라 버퍼에서 바로 파일로 쓴다. */
    class PlaneSource(val buffer: java.nio.ByteBuffer, val rowStride: Int, val pixelStride: Int)

    fun writeStreaming(
        file: File,
        format: Int,
        width: Int,
        height: Int,
        timestampNs: Long,
        sensitivityIso: Int?,
        exposureTimeNs: Long?,
        planes: List<PlaneSource>,
        raw: CaptureFrame.RawInfo?,
    ) {
        FileOutputStream(file).use { fos ->
            val ch = fos.channel
            fun put(block: (DataOutputStream) -> Unit) {
                val bos = ByteArrayOutputStream(64)
                DataOutputStream(bos).use(block)
                val b = java.nio.ByteBuffer.wrap(bos.toByteArray())
                while (b.hasRemaining()) ch.write(b)
            }
            put { out ->
                out.writeInt(MAGIC)
                out.writeInt(format)
                out.writeInt(width)
                out.writeInt(height)
                out.writeLong(timestampNs)
                out.writeBoolean(sensitivityIso != null)
                sensitivityIso?.let { out.writeInt(it) }
                out.writeBoolean(exposureTimeNs != null)
                exposureTimeNs?.let { out.writeLong(it) }
                out.writeInt(planes.size)
            }
            for (p in planes) {
                val b = p.buffer.duplicate()
                put { out ->
                    out.writeInt(p.rowStride)
                    out.writeInt(p.pixelStride)
                    out.writeInt(b.remaining())
                }
                while (b.hasRemaining()) ch.write(b)
            }
            put { out -> writeRaw(out, raw) }
        }
    }

    fun read(file: File): CaptureFrame {
        require(file.length() in 1..268_435_456L) { "invalid capture cache size" }
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            require(input.readInt() == MAGIC) { "capture frame cache mismatch" }
            val format = input.readInt()
            val width = input.readInt()
            val height = input.readInt()
            require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE) { "invalid capture dimensions" }
            val timestampNs = input.readLong()
            val iso = if (input.readBoolean()) input.readInt() else null
            val exposure = if (input.readBoolean()) input.readLong() else null
            val planeCount = input.readInt()
            require(planeCount in 1..3) { "invalid capture plane count" }
            val planes = ArrayList<CaptureFrame.Plane>(planeCount)
            repeat(planeCount) {
                val rowStride = input.readInt()
                val pixelStride = input.readInt()
                val size = input.readInt()
                require(size >= 0 && size <= input.available()) { "truncated capture plane" }
                val bytes = ByteArray(size)
                input.readFully(bytes)
                planes += CaptureFrame.Plane(bytes, rowStride, pixelStride)
            }
            val raw = if (input.readBoolean()) {
                val cfa = input.readInt()
                val black = List(4) { input.readInt() }
                val white = input.readInt()
                val gr = input.readFloat(); val gg = input.readFloat(); val gb = input.readFloat()
                val ccm = if (input.readBoolean()) List(9) { input.readFloat() } else null
                val shading = if (input.readBoolean()) {
                    val cols = input.readInt(); val rows = input.readInt()
                    require(cols in 2..256 && rows in 2..256) { "invalid shading map" }
                    CaptureFrame.RawInfo.Shading(cols, rows, List(cols * rows * 4) { input.readFloat() })
                } else null
                CaptureFrame.RawInfo(cfa, black, white, gr, gg, gb, ccm, shading)
            } else null
            require(input.read() == -1) { "trailing capture data" }
            return CaptureFrame(
                format = format,
                width = width,
                height = height,
                planes = planes,
                timestampNs = timestampNs,
                sensitivityIso = iso,
                exposureTimeNs = exposure,
                raw = raw,
            )
        }
    }

}
