package com.burnin.target.pattern

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import com.burnin.target.util.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 보정을 켠 단색·희소 패턴의 픽셀 계산을 GPU(OpenGL ES 2.0, 화면 밖 프레임버퍼)로 한다.
 *
 * CPU 경로는 패턴마다 화면 전체(Tab S2 3백만 화소)를 Kotlin으로 계산·오차 확산해서 수백 ms~수 초가 걸린다.
 * 여기서는 감쇠맵을 텍스처로 한 번 올리고, 픽셀마다 같은 식(휘도 gain 곱 → gain^(1/γ) → 코드값)을 셰이더로
 * 계산해 결과를 비트맵으로 읽어 온다. 화면 표시는 그대로 뷰가 한다: 창(SurfaceView)을 하나 더 얹으면
 * 구형 기기(Android 7)의 창 관리가 멈춘 적이 있어서, GPU는 계산에만 쓴다.
 * 8비트 계단은 8×8 Bayer ordered dither로 나눈다(블록 평균은 오차 확산과 같고 행 순서 의존이 없다).
 * 희소 패턴은 CPU와 같이 반올림한다(점 하나는 나눌 이웃이 없다).
 * 실패하면 false를 돌려주고, 이후로는 쓰지 않는다(호출자가 CPU 경로로 그린다).
 */
object GpuCorrector {

    class Input(
        val spec: PatternSpec,
        val base: Int,
        val rgb: Bitmap?,
        val wb: Bitmap?,
        val correctionAttenuation: Float,
        val whiteBalanceAttenuation: Float,
        val inverseGamma: Float,
    )

    @Volatile var disabled = false
        private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    // EGL 상수는 클래스 초기화 때 건드리지 않는다(단위 테스트 환경에서 초기화 오류가 난다)
    private var display: EGLDisplay? = null
    private var context: EGLContext? = null
    private var surface: EGLSurface? = null
    private var program = 0
    private val textures = IntArray(3)
    private val framebuffer = IntArray(1)
    private var fbW = 0
    private var fbH = 0
    private val uploaded = arrayOfNulls<Bitmap>(2)
    private var readBuffer: ByteBuffer? = null

    /**
     * [input]을 [out](ARGB_8888, 화면 크기, 수정 가능)에 계산해 넣는다. GPU 스레드에서 돌고 호출 스레드는
     * 끝날 때까지 기다린다. 실패·시간 초과면 false(이후 GPU를 끈다).
     */
    fun render(input: Input, out: Bitmap): Boolean {
        if (disabled) return false
        val h = synchronized(this) {
            handler ?: HandlerThread("gpu-corrector").also { it.start(); thread = it }.let { Handler(it.looper) }
                .also { handler = it }
        }
        var ok = false
        val latch = CountDownLatch(1)
        h.post {
            ok = try {
                ensureContext() && draw(input, out)
            } catch (e: Throwable) {
                AppLog.i("GPU 보정 계산 실패 — CPU로 그림: ${e.message}")
                false
            }
            latch.countDown()
        }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            AppLog.i("GPU 보정 계산 시간 초과 — CPU로 그림")
            disabled = true
            return false
        }
        if (!ok) disabled = true
        return ok
    }

    private fun ensureContext(): Boolean {
        if (context != null) return true
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig" }
        val ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(ctx != null && ctx != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        val surf = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(surf != null && surf != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface" }
        check(EGL14.eglMakeCurrent(display, surf, surf, ctx)) { "eglMakeCurrent" }
        surface = surf
        program = buildProgram()
        GLES20.glGenTextures(3, textures, 0)
        GLES20.glGenFramebuffers(1, framebuffer, 0)
        context = ctx
        AppLog.i("GPU 보정 계산 준비: ${GLES20.glGetString(GLES20.GL_RENDERER)}")
        return true
    }

    private fun draw(f: Input, out: Bitmap): Boolean {
        val w = out.width
        val h = out.height
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
        if (fbW != w || fbH != h) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[2])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[2], 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "framebuffer ${w}x$h" }
            fbW = w
            fbH = h
            readBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        }
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        val hasRgb = bind(0, f.rgb)
        val hasWb = bind(1, f.wb)
        fun u(name: String) = GLES20.glGetUniformLocation(program, name)
        GLES20.glUniform1i(u("uRgb"), 0)
        GLES20.glUniform1i(u("uWb"), 1)
        GLES20.glUniform2f(u("uSize"), w.toFloat(), h.toFloat())
        GLES20.glUniform3f(
            u("uBase"),
            ((f.base shr 16) and 0xFF).toFloat(), ((f.base shr 8) and 0xFF).toFloat(), (f.base and 0xFF).toFloat(),
        )
        GLES20.glUniform1f(u("uCorrAtt"), if (hasRgb) f.correctionAttenuation else 0f)
        GLES20.glUniform1f(u("uWbAtt"), if (hasWb) f.whiteBalanceAttenuation else 0f)
        GLES20.glUniform1f(u("uInvGamma"), f.inverseGamma)
        val s = f.spec
        GLES20.glUniform1f(u("uSparse"), if (s.kind == PatternSpec.Kind.SPARSE) 1f else 0f)
        GLES20.glUniform4f(
            u("uSparseP"),
            s.block.toFloat(), s.pitch.coerceAtLeast(1).toFloat(),
            (s.phaseX * s.block).toFloat(), (s.phaseY * s.block).toFloat(),
        )
        val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        val buf = readBuffer!!
        buf.position(0)
        // 프레임버퍼 행 0 = 화면 위쪽 행(셰이더가 y를 그렇게 셈), RGBA 바이트 = ARGB_8888 메모리 배치
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val err = GLES20.glGetError()
        check(err == GLES20.GL_NO_ERROR) { "GL 오류 0x${Integer.toHexString(err)}" }
        buf.position(0)
        out.copyPixelsFromBuffer(buf)
        return true
    }

    /** 감쇠맵을 텍스처 [unit]에 올린다(같은 비트맵이면 다시 올리지 않음). 맵이 없으면 false. */
    private fun bind(unit: Int, bmp: Bitmap?): Boolean {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[unit])
        if (bmp == null || bmp.isRecycled) return false
        if (uploaded[unit] !== bmp) {
            // 화면과 크기가 같으면 텍셀 중심에서 읽으므로 LINEAR도 원래 값 그대로, 다르면 CPU의 필터 확대와 같다
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            uploaded[unit] = bmp
        }
        return true
    }

    private fun buildProgram(): Int {
        fun compile(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "셰이더 컴파일: ${GLES20.glGetShaderInfoLog(sh)}" }
            return sh
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, VERTEX))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "셰이더 링크: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    private const val VERTEX = """
attribute vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

    // 화면 픽셀 (x, y): 프레임버퍼 행 0을 화면 위쪽 행으로 둔다(glReadPixels가 행 0부터 읽음). 감쇠 = 맵 v/255 × 최대 감쇠.
    private const val FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uRgb;
uniform sampler2D uWb;
uniform vec2 uSize;
uniform vec3 uBase;
uniform float uCorrAtt;
uniform float uWbAtt;
uniform float uInvGamma;
uniform float uSparse;
uniform vec4 uSparseP;

float bayer2(vec2 a) { a = floor(a); return fract(dot(a, vec2(0.5, a.y * 0.75))); }
float bayer4(vec2 a) { return bayer2(0.5 * a) * 0.25 + bayer2(a); }
float bayer8(vec2 a) { return bayer4(0.5 * a) * 0.25 + bayer2(a); }

void main() {
    vec2 px = floor(gl_FragCoord.xy);
    vec2 uv = (px + 0.5) / uSize;
    vec3 c = texture2D(uRgb, uv).rgb;
    vec3 w = texture2D(uWb, uv).rgb;
    vec3 lum = clamp((1.0 - c * uCorrAtt) * (1.0 - w * uWbAtt), 1e-6, 1.0);
    vec3 v = uBase * pow(lum, vec3(uInvGamma));
    vec3 q;
    if (uSparse > 0.5) {
        vec2 b = px - uSparseP.zw;
        bool lit = b.x >= 0.0 && b.y >= 0.0 &&
            mod(b.x, uSparseP.y) < uSparseP.x && mod(b.y, uSparseP.y) < uSparseP.x;
        q = lit ? floor(v + 0.5) : vec3(0.0);
    } else {
        // 문턱 k/64 + 1/128: 평균 0.5로 치우침 없는 반올림
        q = floor(v + bayer8(px) + 0.0078125);
    }
    gl_FragColor = vec4(clamp(q, 0.0, 255.0) / 255.0, 1.0);
}
"""
}
