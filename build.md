Prepare Dependency

    brew install automake automake autoconf  libtool pkg-config m4 gmp bison jq openssl


Build Strongswan (bison hack because of old OSX bison verion)

    ./autogen.sh
    ./configure YACC='/opt/homebrew/opt/bison/bin/bison -y'  --host=arm-linux-androideabi  --disable-kernel-netlink --disable-scripts --disable-gmp
    make dist

Prepare OpenSSL build

    git clone https://github.com/openssl/openssl.git
    export basedir="/home/user"
    export NO_DOCKER=True
    export ANDROID_NDK_ROOT=$basedir"/Library/Android/sdk/ndk/26.1.10909125/"
    export OPENSSL_SRC="openssl"
    export PATH=$ANDROID_NDK_HOME/toolchains/arm-linux-androideabi-4.8/prebuilt/linux-x86_64/bin:$PATH
    export PATH=$basedir/Library/Android/sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/darwin-x86_64/bin/:$PATH

Build OpenSSL

    rm -r ./src/frontends/android/app/src/main/jni/openssl/
    src/frontends/android/openssl/build.sh




