#!/usr/bin/env python3
"""
Zero-Gradle Android APK builder for RunningHub Clear Studio.
Pipeline: Pillow M3 Adaptive Icons -> aapt2 compile/link -> javac --release 11 -> d8 -> zipalign -> apksigner.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

ANDROID_DIR = Path(__file__).resolve().parent
PROJECT_DIR = ANDROID_DIR.parent
MAIN_DIR = ANDROID_DIR / "app" / "src" / "main"
RES_DIR = MAIN_DIR / "res"
JAVA_DIR = MAIN_DIR / "java"
MANIFEST_PATH = MAIN_DIR / "AndroidManifest.xml"
WWW_SRC_DIR = ANDROID_DIR / "www"
TEMPLATE_SRC = PROJECT_DIR / "rh_template.png"
BUILD_DIR = ANDROID_DIR / "build"
DIST_DIR = PROJECT_DIR / "dist"
OUTPUT_APK = DIST_DIR / "RunningHubClearStudio.apk"

KEYSTORE_PATH = Path(os.environ.get("RH_KEYSTORE_PATH", str(ANDROID_DIR / "clearstudio_release.jks")))
KEY_ALIAS = os.environ.get("RH_KEY_ALIAS", "clearstudio")
KEY_PASS_ENV = "RH_KEYSTORE_PASS"
DEFAULT_DEV_PASS = "RunningHubClear2026"
KEY_DNAME = "CN=RunningHub Clear Studio, OU=Mobile, O=RunningHub Clear Studio, L=Local, S=Local, C=US"

MIN_SDK = "26"
TARGET_SDK = "34"
VERSION_CODE = "1"
VERSION_NAME = "1.0.0"


def _version_tuple(name: str) -> tuple[int, ...]:
    nums = re.findall(r"\d+", name)
    return tuple(int(n) for n in nums) if nums else (0,)


def detect_sdk_root() -> Path:
    for env_key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        val = os.environ.get(env_key, "").strip()
        if val:
            return Path(val)

    local_props = ANDROID_DIR / "local.properties"
    if local_props.is_file():
        for line in local_props.read_text(encoding="utf-8", errors="ignore").splitlines():
            line = line.strip()
            if line.startswith("sdk.dir=") and not line.startswith("#"):
                raw = line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "/")
                if raw:
                    return Path(raw)

    candidates: list[Path] = []
    if os.name == "nt":
        local_app = os.environ.get("LOCALAPPDATA", "").strip()
        if local_app:
            candidates.append(Path(local_app) / "Android" / "Sdk")
        candidates.append(Path.home() / "AppData" / "Local" / "Android" / "Sdk")
    elif sys.platform == "darwin":
        candidates.append(Path.home() / "Library" / "Android" / "sdk")
        candidates.append(Path.home() / "Android" / "Sdk")
    else:
        candidates.append(Path.home() / "Android" / "Sdk")
        candidates.append(Path.home() / "android-sdk")

    for cand in candidates:
        if cand.is_dir():
            return cand
    return candidates[0]


def detect_build_tools_dir(sdk_root: Path) -> Path:
    preferred = sdk_root / "build-tools" / "34.0.0"
    if preferred.is_dir():
        return preferred
    bt_root = sdk_root / "build-tools"
    if bt_root.is_dir():
        found = []
        aapt_names = ("aapt2.exe", "aapt2") if os.name == "nt" else ("aapt2",)
        for child in bt_root.iterdir():
            if child.is_dir() and any((child / n).is_file() for n in aapt_names):
                found.append((_version_tuple(child.name), child))
        if found:
            found.sort(key=lambda item: item[0], reverse=True)
            return found[0][1]
    return preferred


def detect_android_jar(sdk_root: Path) -> Path:
    preferred = sdk_root / "platforms" / "android-34" / "android.jar"
    if preferred.is_file():
        return preferred
    plat_root = sdk_root / "platforms"
    if plat_root.is_dir():
        found = []
        for child in plat_root.iterdir():
            jar = child / "android.jar"
            if child.is_dir() and child.name.startswith("android-") and jar.is_file():
                found.append((_version_tuple(child.name), jar))
        if found:
            found.sort(key=lambda item: item[0], reverse=True)
            return found[0][1]
    return preferred


SDK_ROOT = detect_sdk_root()
BUILD_TOOLS_DIR = detect_build_tools_dir(SDK_ROOT)
ANDROID_JAR = detect_android_jar(SDK_ROOT)


def find_tool(name: str, fallback_dir: Path | None = None) -> str:
    exts = (".exe", ".bat", ".cmd") if os.name == "nt" else ("",)
    if fallback_dir is not None:
        for ext in exts:
            candidate = fallback_dir / f"{name}{ext}"
            if candidate.is_file():
                return str(candidate)
    found = shutil.which(name)
    if found:
        return found
    raise FileNotFoundError(
        f"Required tool not found: {name}. Check ANDROID_SDK_ROOT={SDK_ROOT}"
    )


def find_keytool() -> str:
    found = shutil.which("keytool")
    if found:
        return found
    proc = subprocess.run(
        ["java", "-XshowSettings:properties", "-version"],
        capture_output=True,
        check=False,
    )
    combined = (proc.stderr or b"").decode("utf-8", errors="replace")
    m = re.search(r"java\.home\s*=\s*(.+)", combined)
    if m:
        raw_home = m.group(1).strip()
        if len(raw_home) == 2 and raw_home[1] == ":":
            raw_home += "/"
        jh = Path(raw_home)
        for cand in (jh / "bin" / "keytool.exe", jh / "bin" / "keytool", jh.parent / "bin" / "keytool.exe"):
            if cand.is_file():
                return str(cand)
    raise FileNotFoundError("keytool not found in PATH or java.home")


def run_cmd(cmd: list[str], label: str, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    print(f"[build] {label}...")
    proc = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=env,
        check=False,
    )
    if proc.returncode != 0:
        print(f"[ERROR] {label} failed (exit {proc.returncode})")
        if proc.stdout.strip():
            print(proc.stdout.strip())
        if proc.stderr.strip():
            print(proc.stderr.strip())
        sys.exit(proc.returncode)
    return proc


def _draw_squircle_mask(image_mod, size: int, margin: int, power: float = 4.5):
    mask = image_mod.new("L", (size, size), 0)
    px = mask.load()
    cx = (size - 1) * 0.5
    cy = (size - 1) * 0.5
    radius = (size * 0.5) - margin
    inv_r = 1.0 / max(1.0, radius)
    for y in range(size):
        dy = abs(y - cy) * inv_r
        for x in range(size):
            dx = abs(x - cx) * inv_r
            dist = (dx**power + dy**power) ** (1.0 / power)
            if dist <= 1.0 - 1.5 * inv_r:
                px[x, y] = 255
            elif dist < 1.0 + 1.5 * inv_r:
                alpha = int(round(255 * (1.0 - (dist - (1.0 - 1.5 * inv_r)) / (3.0 * inv_r))))
                px[x, y] = max(0, min(255, alpha))
    return mask


def _render_foreground_mark(image_mod, draw_mod, size: int, monochrome: bool = False):
    canvas = image_mod.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = draw_mod.Draw(canvas)
    s = size
    # Crystal frame + diagonal sweep + sparkle emblem
    box_pad = s * 0.27
    lw = max(2, int(round(s * 0.036)))
    rect_col = (255, 255, 255, 255) if monochrome else (154, 165, 186, 220)
    wipe_col = (255, 255, 255, 255) if monochrome else (56, 189, 248, 255)
    spark_col = (255, 255, 255, 255) if monochrome else (238, 242, 248, 255)

    r0 = (box_pad, box_pad, s - box_pad, s - box_pad)
    draw.rounded_rectangle(r0, radius=int(s * 0.08), outline=rect_col, width=lw)

    # Top-left erased watermark dashes
    draw.rounded_rectangle(
        (box_pad + s * 0.06, box_pad + s * 0.06, box_pad + s * 0.22, box_pad + s * 0.13),
        radius=max(2, lw // 2),
        fill=(255, 255, 255, 180) if monochrome else (100, 116, 139, 175),
    )

    # Diagonal wipe beam
    p1 = (s * 0.34, s * 0.66)
    p2 = (s * 0.68, s * 0.32)
    draw.line([p1, p2], fill=wipe_col, width=max(3, int(round(s * 0.05))))
    rad = max(2, int(round(s * 0.025)))
    for pt in (p1, p2):
        draw.ellipse((pt[0] - rad, pt[1] - rad, pt[0] + rad, pt[1] + rad), fill=wipe_col)

    # Four-point sparkle at upper right
    cx, cy = s * 0.60, s * 0.40
    arm = s * 0.11
    waist = s * 0.028
    pts = [
        (cx, cy - arm),
        (cx + waist, cy - waist),
        (cx + arm, cy),
        (cx + waist, cy + waist),
        (cx, cy + arm),
        (cx - waist, cy + waist),
        (cx - arm, cy),
        (cx - waist, cy - waist),
    ]
    draw.polygon(pts, fill=spark_col)
    return canvas


def generate_icons() -> None:
    from PIL import Image, ImageDraw

    densities = {
        "mipmap-mdpi": (48, 108),
        "mipmap-hdpi": (72, 162),
        "mipmap-xhdpi": (96, 216),
        "mipmap-xxhdpi": (144, 324),
        "mipmap-xxxhdpi": (192, 432),
    }

    for folder, (legacy_px, adaptive_px) in densities.items():
        out_dir = RES_DIR / folder
        out_dir.mkdir(parents=True, exist_ok=True)

        fg_hi = _render_foreground_mark(Image, ImageDraw, adaptive_px * 2, monochrome=False)
        fg = fg_hi.resize((adaptive_px, adaptive_px), Image.Resampling.LANCZOS)
        fg.save(out_dir / "ic_launcher_foreground.png")

        mono_hi = _render_foreground_mark(Image, ImageDraw, adaptive_px * 2, monochrome=True)
        mono = mono_hi.resize((adaptive_px, adaptive_px), Image.Resampling.LANCZOS)
        mono.save(out_dir / "ic_launcher_monochrome.png")

        # Legacy squircle icon
        hi = legacy_px * 3
        bg = Image.new("RGBA", (hi, hi), (12, 16, 27, 255))
        mark_hi = _render_foreground_mark(Image, ImageDraw, hi, monochrome=False)
        composed = Image.alpha_composite(bg, mark_hi)
        sq_mask = _draw_squircle_mask(Image, hi, margin=int(round(hi * 0.06)))
        legacy = Image.new("RGBA", (hi, hi), (0, 0, 0, 0))
        legacy.paste(composed, (0, 0), sq_mask)
        legacy.resize((legacy_px, legacy_px), Image.Resampling.LANCZOS).save(out_dir / "ic_launcher.png")

        # Round icon
        rd_mask = Image.new("L", (hi, hi), 0)
        ImageDraw.Draw(rd_mask).ellipse((hi * 0.05, hi * 0.05, hi * 0.95, hi * 0.95), fill=255)
        rnd = Image.new("RGBA", (hi, hi), (0, 0, 0, 0))
        rnd.paste(composed, (0, 0), rd_mask)
        rnd.resize((legacy_px, legacy_px), Image.Resampling.LANCZOS).save(out_dir / "ic_launcher_round.png")


def ensure_keystore(keytool: str, sign_env: dict[str, str]) -> None:
    if KEYSTORE_PATH.is_file():
        return
    KEYSTORE_PATH.parent.mkdir(parents=True, exist_ok=True)
    run_cmd(
        [
            keytool,
            "-genkeypair",
            "-v",
            "-keystore",
            str(KEYSTORE_PATH),
            "-alias",
            KEY_ALIAS,
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "10000",
            "-storepass:env",
            KEY_PASS_ENV,
            "-keypass:env",
            KEY_PASS_ENV,
            "-dname",
            KEY_DNAME,
        ],
        "generate local release keystore",
        env=sign_env,
    )


def build_apk() -> int:
    t0 = time.perf_counter()
    if not TEMPLATE_SRC.is_file():
        raise FileNotFoundError(f"Template image not found at {TEMPLATE_SRC}")

    key_pass = os.environ.get(KEY_PASS_ENV, "").strip() or DEFAULT_DEV_PASS
    sign_env = os.environ.copy()
    sign_env[KEY_PASS_ENV] = key_pass

    aapt2 = find_tool("aapt2", BUILD_TOOLS_DIR)
    d8 = find_tool("d8", BUILD_TOOLS_DIR)
    zipalign = find_tool("zipalign", BUILD_TOOLS_DIR)
    apksigner = find_tool("apksigner", BUILD_TOOLS_DIR)
    javac = find_tool("javac")
    keytool = find_keytool()

    print(f"[build] SDK root    : {SDK_ROOT}")
    print(f"[build] build-tools : {BUILD_TOOLS_DIR}")
    print(f"[build] android.jar : {ANDROID_JAR}")

    generate_icons()
    ensure_keystore(keytool, sign_env)

    if BUILD_DIR.exists():
        shutil.rmtree(BUILD_DIR)
    assets_dir = BUILD_DIR / "assets"
    assets_www = assets_dir / "www"
    gen_dir = BUILD_DIR / "gen"
    classes_dir = BUILD_DIR / "classes"
    dex_dir = BUILD_DIR / "dex"
    for d in (assets_www, gen_dir, classes_dir, dex_dir, DIST_DIR):
        d.mkdir(parents=True, exist_ok=True)

    shutil.copy2(TEMPLATE_SRC, assets_dir / "rh_template.png")
    for src in sorted(WWW_SRC_DIR.rglob("*")):
        if src.is_file():
            rel = src.relative_to(WWW_SRC_DIR)
            dst = assets_www / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)

    res_zip = BUILD_DIR / "compiled_res.zip"
    unaligned_apk = BUILD_DIR / "app-unaligned.apk"
    aligned_apk = BUILD_DIR / "app-aligned.apk"

    run_cmd([aapt2, "compile", "--dir", str(RES_DIR), "-o", str(res_zip)], "aapt2 compile resources")
    run_cmd(
        [
            aapt2,
            "link",
            "-I",
            str(ANDROID_JAR),
            "--manifest",
            str(MANIFEST_PATH),
            "-A",
            str(assets_dir),
            "--java",
            str(gen_dir),
            "--min-sdk-version",
            MIN_SDK,
            "--target-sdk-version",
            TARGET_SDK,
            "--version-code",
            VERSION_CODE,
            "--version-name",
            VERSION_NAME,
            "--replace-version",
            "--auto-add-overlay",
            "-o",
            str(unaligned_apk),
            str(res_zip),
        ],
        "aapt2 link resources & assets",
    )

    java_sources = [str(p) for p in sorted(gen_dir.rglob("*.java"))] + [
        str(p) for p in sorted(JAVA_DIR.rglob("*.java"))
    ]
    run_cmd(
        [
            javac,
            "--release",
            "11",
            "-encoding",
            "UTF-8",
            "-cp",
            str(ANDROID_JAR),
            "-d",
            str(classes_dir),
            *java_sources,
        ],
        "javac compile (--release 11)",
    )

    class_files = [str(p) for p in sorted(classes_dir.rglob("*.class"))]
    run_cmd(
        [
            d8,
            "--release",
            "--min-api",
            MIN_SDK,
            "--lib",
            str(ANDROID_JAR),
            "--output",
            str(dex_dir),
            *class_files,
        ],
        "d8 dex compilation",
    )

    dex_file = dex_dir / "classes.dex"
    with zipfile.ZipFile(unaligned_apk, "a", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.write(dex_file, arcname="classes.dex")

    run_cmd([zipalign, "-p", "-f", "4", str(unaligned_apk), str(aligned_apk)], "zipalign APK")
    run_cmd(
        [
            apksigner,
            "sign",
            "--ks",
            str(KEYSTORE_PATH),
            "--ks-key-alias",
            KEY_ALIAS,
            "--ks-pass",
            f"env:{KEY_PASS_ENV}",
            "--key-pass",
            f"env:{KEY_PASS_ENV}",
            "--v1-signing-enabled",
            "true",
            "--v2-signing-enabled",
            "true",
            "--v3-signing-enabled",
            "true",
            "--out",
            str(OUTPUT_APK),
            str(aligned_apk),
        ],
        "apksigner sign APK (v1+v2+v3)",
        env=sign_env,
    )

    verify = run_cmd([apksigner, "verify", "--verbose", "--print-certs", str(OUTPUT_APK)], "apksigner verify")
    badging = run_cmd([aapt2, "dump", "badging", str(OUTPUT_APK)], "aapt2 dump badging")

    elapsed = time.perf_counter() - t0
    kb = OUTPUT_APK.stat().st_size / 1024.0
    print(f"\n[OK] Built signed APK in {elapsed:.2f}s -> {OUTPUT_APK} ({kb:.1f} KB, {OUTPUT_APK.stat().st_size} bytes)")
    print(verify.stdout.strip())
    for line in badging.stdout.splitlines():
        if line.startswith(("package:", "sdkVersion:", "targetSdkVersion:", "application-label:", "launchable-activity:", "uses-permission:")):
            print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(build_apk())
