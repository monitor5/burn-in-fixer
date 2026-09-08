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
            require(input.read() == -1) { "trailing capture data" }
            return CaptureFrame(
                format = format,
                width = width,
                height = height,
                planes = planes,
                timestampNs = timestampNs,
                sensitivityIso = iso,
                exposureTimeNs = exposure,
            )
        }
    }

}
