# OpenSSL (libcrypto only is linked) for Android. Same source as upstream
# deps/OpenSSL/OpenSSL.cmake, configured with OpenSSL's own android-* targets,
# which locate the compiler through ANDROID_NDK_ROOT and PATH.
get_filename_component(_ndk_bin "${ANDROID_CC}" DIRECTORY)

ExternalProject_Add(dep_OpenSSL
    URL "https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz"
    URL_HASH SHA256=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
    DOWNLOAD_DIR ${DEP_DOWNLOAD_DIR}/OpenSSL
    BUILD_IN_SOURCE ON
    CONFIGURE_COMMAND env "ANDROID_NDK_ROOT=${ANDROID_NDK}" "PATH=${_ndk_bin}:$ENV{PATH}"
        ./Configure ${OPENSSL_ANDROID_TARGET}
        -D__ANDROID_API__=${ANDROID_API_LEVEL}
        "--prefix=${DESTDIR}"
        "--openssldir=${DESTDIR}/ssl"
        "--libdir=lib"
        -fPIC
        ${ORCA_ANDROID_LINKER_FLAGS}
        no-shared no-asm no-dynamic-engine no-tests no-apps
    BUILD_COMMAND   env "ANDROID_NDK_ROOT=${ANDROID_NDK}" "PATH=${_ndk_bin}:$ENV{PATH}" make -j${NPROC} build_libs
    INSTALL_COMMAND env "ANDROID_NDK_ROOT=${ANDROID_NDK}" "PATH=${_ndk_bin}:$ENV{PATH}" make install_dev
)
