#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Build standalone Windows single-file executable (RunningHubClearStudio.exe) via PyInstaller.
"""

import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent


SPEC_TEMPLATE = """# -*- mode: python ; coding: utf-8 -*-
from pathlib import Path
from PyInstaller.utils.hooks import collect_all

ROOT = Path(SPECPATH).resolve().parent

datas = [
    (str(ROOT / "panel_ui.html"), "."),
    (str(ROOT / "rh_template.png"), "."),
    (str(ROOT / "app.ico"), "."),
]
binaries = []
hiddenimports = ["clr"]

for pkg in ("webview", "pythonnet", "clr_loader"):
    d, b, h = collect_all(pkg)
    datas += d
    binaries += b
    hiddenimports += h

EXCLUDES = [
    "torch",
    "torchvision",
    "torchaudio",
    "tensorflow",
    "scipy",
    "matplotlib",
    "pandas",
    "pytest",
    "IPython",
    "tkinter",
    "_tkinter",
    "setuptools",
    "pkg_resources",
    "PyInstaller",
    "pefile",
    "pydoc_data",
    "unittest",
    "doctest",
    "pdb",
    "yaml",
    "_pyrepl",
    "PIL.ImageTk",
    "PIL.ImageCms",
    "PIL._imagingcms",
    "PIL.ImageMath",
    "PIL._imagingmath",
    "PIL.AvifImagePlugin",
    "PIL._avif",
    "numpy.testing",
    "numpy.f2py",
    "numpy._core._multiarray_tests",
    "ssl",
    "_ssl",
    "_hashlib",
    "asyncio",
    "multiprocessing",
    "tarfile",
    "pydoc",
    "decimal",
    "_decimal",
    "_pydecimal",
    "compression",
    "_zstd",
    "_lzma",
    "_bz2",
    "bz2",
    "lzma",
    "sqlite3",
    "_sqlite3",
    "xmlrpc",
    "ftplib",
    "imaplib",
    "poplib",
    "smtplib",
    "ensurepip",
    "venv",
    "webview.__pyinstaller",
    "pythonnet._pyinstaller",
]

a = Analysis(
    [str(ROOT / "rh_panel_app.py")],
    pathex=[],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=EXCLUDES,
    noarchive=False,
    optimize=2,
)

DROP_BIN_KEYWORDS = (
    "opencv_videoio_ffmpeg",
    "libcrypto-",
    "libssl-",
    "_ssl.",
    "_hashlib.",
    "_avif",
    "_tkinter",
    "_imagingtk",
    "_imagingcms",
    "_imagingmath",
    "_multiarray_tests",
    "_yaml",
    "_zstd",
    "_lzma",
    "_bz2",
    "_decimal",
    "_sqlite3",
    "tcl9",
    "tcl8",
    "pywebview-android.jar",
    "python.runtime.xml",
)

def _keep_entry(entry_name: str) -> bool:
    low = entry_name.replace("\\\\", "/").lower()
    if any(k in low for k in DROP_BIN_KEYWORDS):
        return False
    if low.startswith(("_tcl_data/", "_tk_data/")):
        return False
    return True

a.binaries = [b for b in a.binaries if _keep_entry(b[0])]
a.datas = [d for d in a.datas if _keep_entry(d[0])]

pyz = PYZ(a.pure)

if __ONEDIR_MODE__:
    exe = EXE(
        pyz,
        a.scripts,
        [],
        exclude_binaries=True,
        name="RunningHubClearStudio",
        debug=False,
        bootloader_ignore_signals=False,
        strip=False,
        upx=False,
        console=False,
        icon=[str(ROOT / "app.ico")],
    )
    coll = COLLECT(
        exe,
        a.binaries,
        a.datas,
        strip=False,
        upx=False,
        upx_exclude=[],
        name="RunningHubClearStudio",
    )
else:
    exe = EXE(
        pyz,
        a.scripts,
        a.binaries,
        a.datas,
        [],
        name="RunningHubClearStudio",
        debug=False,
        bootloader_ignore_signals=False,
        strip=False,
        upx=False,
        upx_exclude=[],
        runtime_tmpdir=None,
        console=False,
        disable_windowed_traceback=False,
        argv_emulation=False,
        target_arch=None,
        codesign_identity=None,
        entitlements_file=None,
        icon=[str(ROOT / "app.ico")],
    )
"""


def main() -> int:
    os.chdir(ROOT)
    onedir_mode = "--onedir" in sys.argv[1:]
    spec_dir = ROOT / "build"
    spec_dir.mkdir(parents=True, exist_ok=True)
    spec_path = spec_dir / "RunningHubClearStudio.spec"
    spec_path.write_text(
        SPEC_TEMPLATE.replace("__ONEDIR_MODE__", "True" if onedir_mode else "False"),
        encoding="utf-8",
    )

    cmd = [
        sys.executable,
        "-m",
        "PyInstaller",
        "--noconfirm",
        "--clean",
        "--workpath",
        str(ROOT / "build" / "work"),
        "--distpath",
        str(ROOT / "dist"),
        str(spec_path),
    ]

    print("Running PyInstaller build...")
    res = subprocess.run(cmd)
    if res.returncode != 0:
        return res.returncode

    if onedir_mode:
        out_dir = ROOT / "dist" / "RunningHubClearStudio"
        if out_dir.exists():
            total_mb = sum(p.stat().st_size for p in out_dir.rglob("*") if p.is_file()) / (1024 * 1024)
            print(f"\nBuild succeeded: {out_dir} (uncompressed dir {total_mb:.2f} MB)")
    else:
        exe_path = ROOT / "dist" / "RunningHubClearStudio.exe"
        if exe_path.exists():
            mb = exe_path.stat().st_size / (1024 * 1024)
            print(f"\nBuild succeeded: {exe_path} ({mb:.2f} MB, {exe_path.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
