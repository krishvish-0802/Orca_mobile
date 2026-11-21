#include "GlProgram.hpp"

#include <algorithm>
#include <vector>

namespace orca::viewport {

namespace {

GLuint compile(GLenum type, const char* source, std::string& error)
{
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (ok != GL_TRUE) {
        GLint length = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);
        std::vector<char> log(static_cast<size_t>(std::max(length, 1)));
        glGetShaderInfoLog(shader, length, nullptr, log.data());
        error = std::string(type == GL_VERTEX_SHADER ? "vertex shader: " : "fragment shader: ") + log.data();
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

} // namespace

GlProgram::~GlProgram()
{
    if (m_program != 0)
        glDeleteProgram(m_program);
}

bool GlProgram::build(const char* vertex_source, const char* fragment_source, std::string& error)
{
    if (m_program != 0) {
        glDeleteProgram(m_program);
        m_program = 0;
    }
    const GLuint vs = compile(GL_VERTEX_SHADER, vertex_source, error);
    if (vs == 0)
        return false;
    const GLuint fs = compile(GL_FRAGMENT_SHADER, fragment_source, error);
    if (fs == 0) {
        glDeleteShader(vs);
        return false;
    }
    const GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (ok != GL_TRUE) {
        GLint length = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &length);
        std::vector<char> log(static_cast<size_t>(std::max(length, 1)));
        glGetProgramInfoLog(program, length, nullptr, log.data());
        error = std::string("link: ") + log.data();
        glDeleteProgram(program);
        return false;
    }
    m_program = program;
    return true;
}

} // namespace orca::viewport
