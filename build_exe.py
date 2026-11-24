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


def main() -> int:
    os.chdir(ROOT)
    sep = ";" if os.name == "nt" else ":"

    cmd = [
        sys.executable,
        "-m",
        "PyInstaller",
        "--noconfirm",
        "--clean",
        "--onefile",
        "--windowed",
        "--noupx",
        "--name",
        "RunningHubClearStudio",
        "--icon",
        str(ROOT / "app.ico"),
        "--specpath",
        str(ROOT / "build"),
        "--workpath",
        str(ROOT / "build" / "work"),
        "--distpath",
        str(ROOT / "dist"),
        "--add-data",
        f"{ROOT / 'panel_ui.html'}{sep}.",
        "--add-data",
        f"{ROOT / 'rh_template.png'}{sep}.",
        "--add-data",
        f"{ROOT / 'app.ico'}{sep}.",
        "--collect-all",
        "webview",
        "--collect-all",
        "pythonnet",
        "--collect-all",
        "clr_loader",
        "--hidden-import",
        "clr",
        "--exclude-module",
        "torch",
        "--exclude-module",
        "torchvision",
        "--exclude-module",
        "torchaudio",
        "--exclude-module",
        "tensorflow",
        "--exclude-module",
        "scipy",
        "--exclude-module",
        "matplotlib",
        "--exclude-module",
        "pandas",
        "--exclude-module",
        "pytest",
        "--exclude-module",
        "IPython",
        str(ROOT / "rh_panel_app.py"),
    ]

    print("Running PyInstaller build...")
    res = subprocess.run(cmd)
    if res.returncode != 0:
        return res.returncode

    exe_path = ROOT / "dist" / "RunningHubClearStudio.exe"
    if exe_path.exists():
        mb = exe_path.stat().st_size / (1024 * 1024)
        print(f"\nBuild succeeded: {exe_path} ({mb:.2f} MB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
