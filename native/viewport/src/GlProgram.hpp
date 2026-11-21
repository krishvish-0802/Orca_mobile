#pragma once

#include <GLES3/gl3.h>

#include <string>

namespace orca::viewport {

// A linked GLSL ES program. Owns the GL object; valid only in the context that created it.
class GlProgram {
public:
    GlProgram() = default;
    ~GlProgram();
    GlProgram(const GlProgram&) = delete;
    GlProgram& operator=(const GlProgram&) = delete;

    // Compiles and links; returns false and fills `error` on failure.
    bool build(const char* vertex_source, const char* fragment_source, std::string& error);
    // Forgets the GL object without deleting it, after the context was lost.
    void abandon() { m_program = 0; }

    void use() const { glUseProgram(m_program); }
    GLint uniform(const char* name) const { return glGetUniformLocation(m_program, name); }
    bool valid() const { return m_program != 0; }

private:
    GLuint m_program = 0;
};

} // namespace orca::viewport
