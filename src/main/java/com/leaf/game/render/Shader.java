package com.leaf.game.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.lwjgl.opengl.GL20.*;

public class Shader {
    private final int programId;
    private int vertexShaderId;
    private int fragmentShaderId;

    public Shader(String vertexPath, String fragmentPath) {
        programId = glCreateProgram();
        if (programId == 0) throw new RuntimeException("Could not create Shader Program");

        vertexShaderId   = createShader(vertexPath,   GL_VERTEX_SHADER);
        fragmentShaderId = createShader(fragmentPath, GL_FRAGMENT_SHADER);
        link();
    }

    private int createShader(String path, int shaderType) {
        String shaderCode;
        try {
            shaderCode = new String(Files.readAllBytes(Paths.get(path)));
        } catch (Exception e) {
            throw new RuntimeException("Error reading shader file: " + path, e);
        }

        int shaderId = glCreateShader(shaderType);
        if (shaderId == 0) throw new RuntimeException("Error creating shader.");

        glShaderSource(shaderId, shaderCode);
        glCompileShader(shaderId);

        if (glGetShaderi(shaderId, GL_COMPILE_STATUS) == 0)
            throw new RuntimeException("Error compiling shader: " + glGetShaderInfoLog(shaderId, 1024));

        glAttachShader(programId, shaderId);
        return shaderId;
    }

    private void link() {
        glLinkProgram(programId);
        if (glGetProgrami(programId, GL_LINK_STATUS) == 0)
            throw new RuntimeException("Error linking shader: " + glGetProgramInfoLog(programId, 1024));

        glDetachShader(programId, vertexShaderId);
        glDetachShader(programId, fragmentShaderId);
        glDeleteShader(vertexShaderId);
        glDeleteShader(fragmentShaderId);
    }

    public void bind()   { glUseProgram(programId); }
    public void unbind() { glUseProgram(0); }

    // 4×4 matrix uniform (used for MVP)
    public void setUniform(String name, Matrix4f value) {
        int loc = glGetUniformLocation(programId, name);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(16);
            value.get(buf);
            glUniformMatrix4fv(loc, false, buf);
        }
    }

    // Single float uniform  (e.g. ambientStrength, sunStrength)
    public void setUniform(String name, float value) {
        glUniform1f(glGetUniformLocation(programId, name), value);
    }

    // Vec3 uniform  (e.g. sunDirection)
    public void setUniform(String name, Vector3f value) {
        glUniform3f(glGetUniformLocation(programId, name), value.x, value.y, value.z);
    }

    public void cleanup() {
        unbind();
        if (programId != 0) glDeleteProgram(programId);
    }

    // Single int uniform (e.g. boolean flags)
    public void setUniform(String name, int value) {
        glUniform1i(glGetUniformLocation(programId, name), value);
    }
}