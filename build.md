Prepare Dependency

    brew install automake automake autoconf  libtool pkg-config m4 gmp bison jq openssl


Build Strongswan (bison hack because of old OSX bison verion)

    ./autogen.sh
    ./configure YACC='/opt/homebrew/opt/bison/bin/bison -y'  --disable-kernel-netlink --disable-scripts --disable-gmp --host=arm-linux-androideabi
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

https://github.com/android/ndk/issues/1992

    nano  src/frontends/android/openssl/compile.sh -> add no-asm  to OPTIONS
    rm -r ./src/frontends/android/app/src/main/jni/openssl/
    src/frontends/android/openssl/build.sh
    cd openssl
	sed -E -i '' -e '/[.]hidden.*OPENSSL_armcap_P/d' -e '/[.]extern.*OPENSSL_armcap_P/ {p; s/extern/hidden/; }' crypto/*arm*pl crypto/*/asm/*arm*pl
    cd ..
    src/frontends/android/openssl/build.sh


Build signed Apk + Bundle

Rename to perfect-privacy-version.aab and perfect-privacy-version.apk

Create new github release with tag android-pp-version, add files


