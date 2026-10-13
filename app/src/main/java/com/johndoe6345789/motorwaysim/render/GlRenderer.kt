package com.johndoe6345789.motorwaysim.render

import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Draws [Frame]s with OpenGL ES 3.0. All methods must run on the GL thread. */
class GlRenderer(private val atlas: SignAtlas) {
    private var lit = 0
    private var sign = 0
    private var glowProgram = 0
    private var shadowProgram = 0
    private var skyProgram = 0
    private var signBuffer = 0
    private var glowBuffer = 0
    private var skyBuffer = 0
    private var atlasTexture = 0
    private var atlasVersion = -1
    private var shadowTexture = 0
    private var shadowFbo = 0
    private var shadowsOk = false
    private val uploaded = HashSet<Mesh>()
    private var scratch: FloatBuffer = alloc(1 shl 16)
    private val uniforms = HashMap<String, Int>()

    /** Call from onSurfaceCreated: (re)creates every GL object. */
    fun init(cached: List<Mesh>) {
        for (m in cached) m.buffer = 0
        for (m in uploaded) m.buffer = 0
        uploaded.clear()
        uniforms.clear()
        lit = program(Shaders.LIT_VS, Shaders.LIT_FS)
        sign = program(Shaders.SIGN_VS, Shaders.SIGN_FS)
        glowProgram = program(Shaders.GLOW_VS, Shaders.GLOW_FS)
        shadowProgram = program(Shaders.SHADOW_VS, Shaders.SHADOW_FS)
        skyProgram = program(Shaders.SKY_VS, Shaders.SKY_FS)
        val ids = IntArray(4)
        GLES30.glGenBuffers(3, ids, 0)
        signBuffer = ids[0]
        glowBuffer = ids[1]
        skyBuffer = ids[2]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, skyBuffer)
        val tri = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
        val fb = alloc(tri.size).put(tri).position(0) as FloatBuffer
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, tri.size * 4, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glGenTextures(1, ids, 3)
        atlasTexture = ids[3]
        atlasVersion = -1
        createShadowMap()
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
    }

    private fun createShadowMap() {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        shadowTexture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTexture)
        val size = Scene3D.SHADOW_MAP_SIZE
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, size, size, 0,
            GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
        GLES30.glGenFramebuffers(1, ids, 0)
        shadowFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, shadowTexture, 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
        GLES30.glReadBuffer(GLES30.GL_NONE)
        shadowsOk = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        if (!shadowsOk) Log.w(TAG, "shadow map not supported; drawing without shadows")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun u(program: Int, name: String): Int =
        uniforms.getOrPut("$program/$name") { GLES30.glGetUniformLocation(program, name) }

    fun draw(f: Frame, width: Int, height: Int, evicted: MutableList<Mesh>) {
        for (m in evicted) release(m)
        evicted.clear()
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        // 1. Shadow map: depth from the sun.
        if (shadowsOk) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
            GLES30.glViewport(0, 0, Scene3D.SHADOW_MAP_SIZE, Scene3D.SHADOW_MAP_SIZE)
            GLES30.glDepthMask(true)
            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(2f, 4f)
            GLES30.glUseProgram(shadowProgram)
            GLES30.glUniformMatrix4fv(u(shadowProgram, "uLightViewProj"), 1, false, f.lightViewProj, 0)
            GLES30.glDisableVertexAttribArray(1)
            GLES30.glDisableVertexAttribArray(2)
            var last: Mesh? = null
            for (i in 0 until f.count) {
                val item = f.item(i)
                if (!item.castsShadow) continue
                val mesh = item.mesh ?: continue
                if (mesh.vertexCount == 0) continue
                if (mesh !== last) {
                    upload(mesh)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mesh.buffer)
                    GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, Mesh.STRIDE * 4, 0)
                    last = mesh
                }
                GLES30.glUniformMatrix4fv(u(shadowProgram, "uModel"), 1, false, item.model, 0)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, mesh.vertexCount)
            }
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(FOG[0], FOG[1], FOG[2], 1f)
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        // 2. Sky.
        GLES30.glUseProgram(skyProgram)
        GLES30.glUniformMatrix4fv(u(skyProgram, "uInvViewProj"), 1, false, f.invViewProj, 0)
        GLES30.glUniform3f(u(skyProgram, "uCam"), f.camX, f.camY, f.camZ)
        GLES30.glUniform3f(u(skyProgram, "uSun"), SUN[0], SUN[1], SUN[2])
        GLES30.glUniform3f(u(skyProgram, "uFogColor"), FOG[0], FOG[1], FOG[2])
        GLES30.glDepthMask(false)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, skyBuffer)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDepthMask(true)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glEnableVertexAttribArray(2)

        // 3. Lit geometry.
        GLES30.glUseProgram(lit)
        GLES30.glUniformMatrix4fv(u(lit, "uViewProj"), 1, false, f.viewProj, 0)
        GLES30.glUniformMatrix4fv(u(lit, "uShadowMatrix"), 1, false, f.shadowMatrix, 0)
        GLES30.glUniform3f(u(lit, "uSun"), SUN[0], SUN[1], SUN[2])
        GLES30.glUniform3f(u(lit, "uCam"), f.camX, f.camY, f.camZ)
        GLES30.glUniform3f(u(lit, "uFogColor"), FOG[0], FOG[1], FOG[2])
        GLES30.glUniform2f(u(lit, "uFog"), f.fogStart, f.fogEnd)
        GLES30.glUniform2f(u(lit, "uOrigin"), f.originX.toFloat(), f.originY.toFloat())
        GLES30.glUniform1f(u(lit, "uShadowOn"), if (shadowsOk) 1f else 0f)
        GLES30.glUniform1f(u(lit, "uShadowTexel"), 1f / Scene3D.SHADOW_MAP_SIZE)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTexture)
        GLES30.glUniform1i(u(lit, "uShadowMap"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        var lastMesh: Mesh? = null
        for (i in 0 until f.count) {
            val item = f.item(i)
            val mesh = item.mesh ?: continue
            if (mesh.vertexCount == 0) continue
            if (mesh !== lastMesh) {
                upload(mesh)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mesh.buffer)
                val stride = Mesh.STRIDE * 4
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
                GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
                GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 24)
                lastMesh = mesh
            }
            GLES30.glUniformMatrix4fv(u(lit, "uModel"), 1, false, item.model, 0)
            val t = item.tint
            GLES30.glUniform4f(u(lit, "uTint"), ((t shr 16) and 0xFF) / 255f, ((t shr 8) and 0xFF) / 255f, (t and 0xFF) / 255f, 1f)
            GLES30.glUniform1f(u(lit, "uGround"), if (item.ground) 1f else 0f)
            GLES30.glUniform1f(u(lit, "uShine"), item.shine)
            GLES30.glUniform1f(u(lit, "uFogMax"), item.fogMax)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, mesh.vertexCount)
        }

        // 4. Sign faces (front faces only, so their backs stay blank).
        if (f.signs.size > 0) {
            updateAtlas()
            GLES30.glUseProgram(sign)
            GLES30.glUniformMatrix4fv(u(sign, "uViewProj"), 1, false, f.viewProj, 0)
            GLES30.glUniform3f(u(sign, "uCam"), f.camX, f.camY, f.camZ)
            GLES30.glUniform3f(u(sign, "uFogColor"), FOG[0], FOG[1], FOG[2])
            GLES30.glUniform2f(u(sign, "uFog"), f.fogStart, f.fogEnd)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasTexture)
            GLES30.glUniform1i(u(sign, "uTex"), 0)
            stream(signBuffer, f.signs)
            GLES30.glDisableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12)
            GLES30.glEnable(GLES30.GL_CULL_FACE)
            GLES30.glCullFace(GLES30.GL_BACK)
            GLES30.glFrontFace(GLES30.GL_CCW)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, f.signs.size / 5)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glEnableVertexAttribArray(2)
        }

        // 5. Additive light glows.
        if (f.glow.size > 0) {
            GLES30.glUseProgram(glowProgram)
            GLES30.glUniformMatrix4fv(u(glowProgram, "uViewProj"), 1, false, f.viewProj, 0)
            GLES30.glUniform3f(u(glowProgram, "uRight"), f.right[0], f.right[1], f.right[2])
            GLES30.glUniform3f(u(glowProgram, "uUp"), f.up[0], f.up[1], f.up[2])
            GLES30.glUniform3f(u(glowProgram, "uCam"), f.camX, f.camY, f.camZ)
            GLES30.glUniform2f(u(glowProgram, "uFog"), f.fogStart, f.fogEnd)
            stream(glowBuffer, f.glow)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 40, 0)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 40, 12)
            GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, 40, 24)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            GLES30.glDepthMask(false)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, f.glow.size / 10)
            GLES30.glDepthMask(true)
            GLES30.glDisable(GLES30.GL_BLEND)
        }
    }

    private fun upload(mesh: Mesh) {
        if (mesh.buffer != 0) return
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        mesh.buffer = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mesh.buffer)
        val fb = alloc(mesh.data.size)
        fb.put(mesh.data).position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.data.size * 4, fb, GLES30.GL_STATIC_DRAW)
        uploaded += mesh
    }

    private fun release(mesh: Mesh) {
        if (mesh.buffer == 0) return
        GLES30.glDeleteBuffers(1, intArrayOf(mesh.buffer), 0)
        mesh.buffer = 0
        uploaded -= mesh
    }

    private fun stream(buffer: Int, list: FloatList) {
        if (scratch.capacity() < list.size) scratch = alloc(list.size * 2)
        scratch.clear()
        scratch.put(list.data, 0, list.size).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, list.size * 4, scratch, GLES30.GL_STREAM_DRAW)
    }

    private fun updateAtlas() {
        if (atlasVersion == atlas.version) return
        atlasVersion = atlas.version
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, atlas.bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    }

    private fun program(vs: String, fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) Log.e(TAG, "link failed: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val status = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) Log.e(TAG, "compile failed: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    companion object {
        private const val TAG = "GlRenderer"

        /** Haze colour: fog and horizon. */
        val FOG = floatArrayOf(0.76f, 0.83f, 0.9f)

        /** Direction towards the sun (normalised): afternoon sun in the south-west. */
        val SUN = floatArrayOf(-0.42f, -0.55f, 0.72f).let { s ->
            val l = kotlin.math.sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2])
            floatArrayOf(s[0] / l, s[1] / l, s[2] / l)
        }

        private fun alloc(floats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
}
