# GMP for Android. Same source and patch as upstream deps/GMP/GMP.cmake; the
# configure line targets the NDK triple instead of a desktop host.
include("${CMAKE_CURRENT_LIST_DIR}/AndroidAutotools.cmake")

ExternalProject_Add(dep_GMP
    URL https://ftp.gnu.org/gnu/gmp/gmp-6.3.0.tar.xz
        https://github.com/SoftFever/OrcaSlicer_deps/releases/download/gmp-6.3.0/gmp-6.3.0.tar.xz
    URL_HASH SHA256=a3c2b80201b89e68616f4ad30bc66aee4927c3ce50e33929ca819d5c43538898
    DOWNLOAD_DIR ${DEP_DOWNLOAD_DIR}/GMP
    PATCH_COMMAND ${GIT_EXECUTABLE} apply --verbose ${UPSTREAM_DEPS_DIR}/GMP/0001-GMP_GCC15.patch
    BUILD_IN_SOURCE ON
    CONFIGURE_COMMAND env ${ORCA_ANDROID_ENV} ./configure
        --host=${TOOLCHAIN_PREFIX}
        --enable-shared=no --enable-static=yes --enable-cxx=yes
        --with-pic
        --prefix=${DESTDIR}
    BUILD_COMMAND   make -j${NPROC}
    INSTALL_COMMAND make install
)
