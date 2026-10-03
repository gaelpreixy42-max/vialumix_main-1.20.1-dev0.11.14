package com.vialumix.rt;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Native (shaderpack-less) mode: draws the ray-traced image over the vanilla world. The fragment depth is
 * reconstructed from the RT primary distance, so entities, the hand and anything closer than the ray-traced
 * surface keep winning the depth test; sky pixels (no RT hit) are discarded and keep vanilla's sky.
 */
final class RtComposite {
    private static final Logger LOGGER = LoggerFactory.getLogger("vialumix-rt");
    private static final String VERTEX = """
            #version 150
            out vec2 uv;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                uv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 150
            uniform sampler2D rtColor;
            uniform vec4 camera;   // tanHalfFovX, tanHalfFovY, P22, P32
            uniform vec3 params;   // fade start, fade end, exposure
            in vec2 uv;
            out vec4 outColor;
            void main() {
                vec4 c = texture(rtColor, uv);
                float dist = c.a;
                if (!(dist > 0.0)) discard;
                vec2 ndc = uv * 2.0 - 1.0;
                float dirLen = length(vec3(ndc.x * camera.x, ndc.y * camera.y, 1.0));
                float z = max(dist / dirLen - 0.02, 0.05);
                gl_FragDepth = clamp(((camera.z * -z + camera.w) / z) * 0.5 + 0.5, 0.0, 1.0);
                vec3 x = max(c.rgb, vec3(0.0)) * params.z;
                vec3 col = clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
                col = pow(col, vec3(1.0 / 2.2));
                outColor = vec4(col, 1.0 - smoothstep(params.x, params.y, dist));
            }
            """;

    private static int program;
    private static int vao;
    private static int uColor, uCamera, uParams;
    private static boolean failed;

    private RtComposite() {}

    static void draw(int texture, float tanX, float tanY, float p22, float p32, float fadeStart, float fadeEnd, float exposure) {
        if (failed) return;
        if (program == 0 && !init()) { failed = true; return; }

        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcA = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstA = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        try {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL20.glUniform1i(uColor, 0);
            GL20.glUniform4f(uCamera, tanX, tanY, p22, p32);
            GL20.glUniform3f(uParams, fadeStart, fadeEnd, exposure);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } finally {
            GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcA, dstA);
            set(GL11.GL_BLEND, blend);
            set(GL11.GL_CULL_FACE, cull);
            set(GL11.GL_SCISSOR_TEST, scissor);
            set(GL11.GL_DEPTH_TEST, depthTest);
            GL11.glDepthFunc(depthFunc);
            GL11.glDepthMask(depthMask);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexture);
            GL13.glActiveTexture(prevActive);
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
        }
    }

    private static void set(int capability, boolean enabled) {
        if (enabled) GL11.glEnable(capability); else GL11.glDisable(capability);
    }

    private static boolean init() {
        int vs = compile(GL20.GL_VERTEX_SHADER, VERTEX);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
        if (vs == 0 || fs == 0) return false;
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            LOGGER.error("Vialumix RT composite program failed to link: {}", GL20.glGetProgramInfoLog(p));
            GL20.glDeleteProgram(p);
            return false;
        }
        uColor = GL20.glGetUniformLocation(p, "rtColor");
        uCamera = GL20.glGetUniformLocation(p, "camera");
        uParams = GL20.glGetUniformLocation(p, "params");
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(prevVao);
        program = p;
        LOGGER.info("Vialumix RT composite pass ready.");
        return true;
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            LOGGER.error("Vialumix RT composite shader failed to compile: {}", GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
