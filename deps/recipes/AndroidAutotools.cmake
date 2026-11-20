# Environment shared by the autotools/perl recipes (GMP, MPFR, OpenSSL).
# CMake-based recipes get the same compilers through the NDK toolchain file.
set(ORCA_ANDROID_CFLAGS "-O2 -DNDEBUG -fPIC -DPIC")
set(ORCA_ANDROID_ENV
    "CC=${ANDROID_CC}"
    "CXX=${ANDROID_CXX}"
    "AR=${ANDROID_AR}"
    "RANLIB=${ANDROID_RANLIB}"
    "STRIP=${ANDROID_STRIP}"
    "CFLAGS=${ORCA_ANDROID_CFLAGS}"
    "CXXFLAGS=${ORCA_ANDROID_CFLAGS}"
    "LDFLAGS=${ORCA_ANDROID_LINKER_FLAGS}")
