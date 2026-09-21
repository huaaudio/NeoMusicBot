"""Read versions from the libraries themselves, not Canvas's header constants."""
import ctypes
import json
from pathlib import Path
import sys


def read_versions(directory):
    def library(name):
        return ctypes.CDLL(str(directory / name))

    versions = {}
    for component, filename, function in (
        ("cairo", "libcairo.so.2", "cairo_version_string"),
        ("pango", "libpango-1.0.so.0", "pango_version_string"),
        ("harfbuzz", "libharfbuzz.so.0", "hb_version_string"),
    ):
        reader = getattr(library(filename), function)
        reader.restype = ctypes.c_char_p
        versions[component] = reader().decode("ascii")
    for component, filename, prefix in (
        ("librsvg", "librsvg-2.so.2", "rsvg"),
        ("glib", "libglib-2.0.so.0", "glib"),
    ):
        handle = library(filename)
        versions[component] = ".".join(str(ctypes.c_uint.in_dll(
            handle, prefix + "_" + part + "_version").value) for part in ("major", "minor", "micro"))
    fontconfig = library("libfontconfig.so.1")
    fontconfig.FcGetVersion.restype = ctypes.c_int
    version = fontconfig.FcGetVersion()
    versions["fontconfig"] = f"{version // 10000}.{version // 100 % 100}.{version % 100}"
    freetype = library("libfreetype.so.6")
    handle = ctypes.c_void_p()
    freetype.FT_Init_FreeType.argtypes = [ctypes.POINTER(ctypes.c_void_p)]
    freetype.FT_Done_FreeType.argtypes = [ctypes.c_void_p]
    freetype.FT_Library_Version.argtypes = [ctypes.c_void_p, *([ctypes.POINTER(ctypes.c_int)] * 3)]
    if freetype.FT_Init_FreeType(ctypes.byref(handle)):
        raise RuntimeError("FreeType initialization failed")
    try:
        values = [ctypes.c_int() for _ in range(3)]
        freetype.FT_Library_Version(handle, *(ctypes.byref(value) for value in values))
        versions["freetype"] = ".".join(str(value.value) for value in values)
    finally:
        if freetype.FT_Done_FreeType(handle):
            raise RuntimeError("FreeType shutdown failed")
    return versions


if __name__ == "__main__":
    print(json.dumps(read_versions(Path(sys.argv[1]).resolve()), sort_keys=True))
