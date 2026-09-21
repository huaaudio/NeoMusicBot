#!/usr/bin/env bash
set -euo pipefail
work="$1"
jobs="$2"
node="${work}/tools/node/node-v24.21.0-linux-x64"
export PATH="${node}/bin:${work}/tools/rust/bin:${work}/tools/cargo-c:${work}/sdk/usr/bin:/usr/bin:/bin"
export PYTHONPATH="${work}/tools/meson:${work}/sdk/usr/lib/python3/dist-packages"
export PKG_CONFIG_SYSROOT_DIR="${work}/sdk"
export PKG_CONFIG_LIBDIR="${work}/sdk/usr/lib/x86_64-linux-gnu/pkgconfig:${work}/sdk/usr/share/pkgconfig"
export PKG_CONFIG_PATH="${PKG_CONFIG_LIBDIR}"
export LD_LIBRARY_PATH="${work}/sdk/usr/lib/x86_64-linux-gnu"
export CFLAGS="-O2 -I${work}/sdk/usr/include"
export CXXFLAGS="${CFLAGS}"
export LDFLAGS="-L${work}/sdk/usr/lib/x86_64-linux-gnu"
meson=(python3 -m mesonbuild.mesonmain)
build() {
    local name="$1" version="$2"
    shift 2
    local source="${work}/sources/${name}/${name}-${version}"
    local destination="${work}/build/${name}"
    local arguments=()
    if [[ -f "${destination}/build.ninja" ]]; then arguments=(--reconfigure --clearcache); fi
    "${meson[@]}" setup "${arguments[@]}" "${destination}" "${source}" \
        --prefix=/usr --libdir=lib/x86_64-linux-gnu --buildtype=release --wrap-mode=nodownload "$@"
    "${meson[@]}" compile -C "${destination}" -j "${jobs}"
    "${meson[@]}" install -C "${destination}" --destdir="${work}/sdk"
}
build glib 2.90.0 -Dtests=false -Dintrospection=disabled -Dsysprof=disabled \
    -Dlibmount=disabled -Dselinux=disabled -Dnls=disabled
build freetype 2.14.3 -Dharfbuzz=enabled -Dbrotli=enabled -Dbzip2=enabled -Dpng=enabled -Dzlib=system
build fontconfig 2.18.3 -Ddoc=disabled -Ddoc-man=disabled -Ddoc-txt=disabled -Ddoc-pdf=disabled \
    -Ddoc-html=disabled -Dtests=disabled -Dcache-build=disabled -Dnls=disabled -Dtools=disabled -Dxml-backend=expat
build cairo 1.18.6 -Dtests=disabled -Dxlib=disabled -Dxcb=disabled -Dfontconfig=enabled \
    -Dfreetype=enabled -Dpng=enabled -Dzlib=enabled -Dglib=enabled
build harfbuzz 14.5.0 -Dtests=disabled -Ddocs=disabled -Dutilities=disabled \
    -Dintrospection=disabled -Dglib=enabled -Dgobject=enabled -Dfreetype=enabled \
    -Dcairo=enabled -Dgraphite2=enabled -Dicu=enabled -Dgpu_demo=disabled
# Bootstrap the FreeType/HarfBuzz cycle with the SDK, then compile FreeType
# against the final HarfBuzz headers and library.
build freetype 2.14.3 -Dharfbuzz=enabled -Dbrotli=enabled -Dbzip2=enabled -Dpng=enabled -Dzlib=system
build pango 1.58.2 -Dbuild-testsuite=false -Dbuild-examples=false -Dintrospection=disabled \
    -Dsysprof=disabled -Dxft=disabled -Dfontconfig=enabled -Dfreetype=enabled -Dcairo=enabled
build librsvg 2.63.2 -Dtests=false -Dintrospection=disabled -Ddocs=disabled -Dvala=disabled \
    -Drsvg-convert=disabled -Dpixbuf=enabled -Davif=enabled
cd "${work}/sources/canvas/package"
# Canvas's GIF auto-detection searches host library directories. These three
# backends are provided by the fixed SDK and must not depend on host packages.
node "${work}/build-tools/node_modules/node-gyp/bin/node-gyp.js" rebuild --jobs="${jobs}" --nodedir="${node}" \
    -- -Dwith_jpeg=true -Dwith_gif=true -Dwith_rsvg=true
echo 'NativeSourceBuildCompleted=true'
