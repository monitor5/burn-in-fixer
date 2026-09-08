package com.burnin.scanner.camera

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class CaptureFrameCodecTest {
    @get:Rule val temp=TemporaryFolder()
    private fun frame()=CaptureFrame(35,2,2,listOf(CaptureFrame.Plane(byteArrayOf(1,2,3,4),2,1)),1234,200,10000)
    @Test fun roundTripPreservesSensorMetadataStridesAndPlaneBytes() {
        for(f in listOf(frame(),frame().copy(sensitivityIso=null,exposureTimeNs=null))) {
            val file=temp.newFile();CaptureFrameCodec.write(file,f);val read=CaptureFrameCodec.read(file)
            assertEquals(f.copy(planes=emptyList()),read.copy(planes=emptyList()))
            assertEquals(2,read.planes[0].rowStride);assertEquals(1,read.planes[0].pixelStride);assertArrayEquals(f.planes[0].bytes,read.planes[0].bytes)
        }
    }
    @Test fun badMagicTruncationAndTrailingDataFailClosed() {
        val file=temp.newFile();CaptureFrameCodec.write(file,frame());val good=file.readBytes()
        for(length in listOf(0,1,8,good.size-1)) {file.writeBytes(good.take(length).toByteArray());assertThrows(Exception::class.java){CaptureFrameCodec.read(file)}}
        file.writeBytes(good+byteArrayOf(1));assertThrows(IllegalArgumentException::class.java){CaptureFrameCodec.read(file)}
        file.writeBytes(good);RandomAccessFile(file,"rw").use{it.writeInt(0)};assertThrows(IllegalArgumentException::class.java){CaptureFrameCodec.read(file)}
    }
    @Test fun hostilePlaneLengthsAreRejectedBeforeAllocation() {
        val file=temp.newFile();CaptureFrameCodec.write(file,frame().copy(sensitivityIso=null,exposureTimeNs=null))
        // magic/format/width/height (16), timestamp (8), optional flags (2), plane count (4), strides (8)
        RandomAccessFile(file,"rw").use{it.seek(38);it.writeInt(Int.MAX_VALUE)}
        assertThrows(IllegalArgumentException::class.java){CaptureFrameCodec.read(file)}
    }
}
