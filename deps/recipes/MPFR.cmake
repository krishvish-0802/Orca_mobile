# MPFR for Android. Same source as upstream deps/MPFR/MPFR.cmake.
include("${CMAKE_CURRENT_LIST_DIR}/AndroidAutotools.cmake")

ExternalProject_Add(dep_MPFR
    URL https://ftp.gnu.org/gnu/mpfr/mpfr-4.2.2.tar.bz2
        https://www.mpfr.org/mpfr-4.2.2/mpfr-4.2.2.tar.bz2
    URL_HASH SHA256=9ad62c7dc910303cd384ff8f1f4767a655124980bb6d8650fe62c815a231bb7b
    DOWNLOAD_DIR ${DEP_DOWNLOAD_DIR}/MPFR
    BUILD_IN_SOURCE ON
    CONFIGURE_COMMAND env ${ORCA_ANDROID_ENV} ./configure
        --host=${TOOLCHAIN_PREFIX}
        --enable-shared=no --enable-static=yes
        --with-pic
        --with-gmp=${DESTDIR}
        --prefix=${DESTDIR}
    BUILD_COMMAND   make -j${NPROC}
    INSTALL_COMMAND make install
    DEPENDS dep_GMP
)
