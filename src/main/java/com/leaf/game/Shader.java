package com.leaf.game;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL33.*;

public class Shader {
    private final int programId;
    private int vertexShaderId;
    private int fragmentShaderId;

    public Shader(String vertexPath, String fragmentPath) {
        // Create an empty program on the GPU
        programId = glCreateProgram();
        if (programId == 0) {
            throw new RuntimeException("Could not create Shader Program");
        }

        // Load and compile the two shaders safely, using the createShader method
        vertexShaderId = createShader(vertexPath, GL_VERTEX_SHADER);
        fragmentShaderId = createShader(fragmentPath, GL_FRAGMENT_SHADER);

        // Link them together into the final program
        link();
    }

    private int createShader(String path, int shaderType) {
        String shaderCode;
        try {
            // Read the shader text file
            shaderCode = new String(Files.readAllBytes(Paths.get(path)));
        } catch (Exception e) {
            throw new RuntimeException("Error reading shader file: " + path, e);
        }

        // Ask the GPU to create a shader
        int shaderId = glCreateShader(shaderType);
        if (shaderId == 0) {
            throw new RuntimeException("Error creating shader.");
        }

        // Give the GPU the source code and tell it to compile
        glShaderSource(shaderId, shaderCode);
        glCompileShader(shaderId);

        // Check if it compiled successfully (Check for syntax errors in your GLSL)
        if (glGetShaderi(shaderId, GL_COMPILE_STATUS) == 0) {
            throw new RuntimeException("Error compiling shader: " + glGetShaderInfoLog(shaderId, 1024));
        }

        // Attach it to our main program
        glAttachShader(programId, shaderId);
        return shaderId;
    }

    private void link() {
        // Link the program
        glLinkProgram(programId);
        if (glGetProgrami(programId, GL_LINK_STATUS) == 0) {
            throw new RuntimeException("Error linking shader code: " + glGetProgramInfoLog(programId, 1024));
        }

        // Once linked, we don't need the individual shader pieces taking up memory anymore
        glDetachShader(programId, vertexShaderId);
        glDetachShader(programId, fragmentShaderId);
        glDeleteShader(vertexShaderId);
        glDeleteShader(fragmentShaderId);
    }

    public void bind() {
        // Tell OpenGL to USE this shader program
        glUseProgram(programId);
    }

    public void unbind() {
        // Tell OpenGL to STOP using this shader program
        glUseProgram(0);
    }

    public void setUniform(String uniformName, Matrix4f value) {
        // Find the "mvp" variable in the shader
        int location = glGetUniformLocation(programId, uniformName);

        // Push the 4x4 matrix data from Java to the GPU
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(16);
            value.get(buffer);
            glUniformMatrix4fv(location, false, buffer);
        }
    }

    public void cleanup() {
        unbind();
        if (programId != 0) {
            glDeleteProgram(programId);
        }
    }
}