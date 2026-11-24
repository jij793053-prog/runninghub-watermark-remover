#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Universal RunningHub AI Watermark Remover
- Mode A: Exact pixel-level inverse alpha unblending for RGBA PNGs containing RunningHub's alpha signature.
- Mode B: Multi-scale sub-pixel gradient template matching + eroded-core hybrid unblending for JPG/WebP/RGB PNGs.
"""

import argparse
import os
import sys
import time
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

Image.MAX_IMAGE_PIXELS = None

IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tif", ".tiff"}
SKIP_SUFFIX = "_去水印"


def _resource_dir() -> Path:
    if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
        return Path(sys._MEIPASS)
    return Path(__file__).resolve().parent


TEMPLATE_PATH = _resource_dir() / "rh_template.png"


def load_image_unicode(path: Path) -> tuple[np.ndarray, str]:
    """Load image safely on Windows even with Unicode/Chinese paths."""
    with Image.open(path) as im:
        orig_mode = im.mode
        if orig_mode in ("RGBA", "LA") or ("transparency" in im.info):
            return np.asarray(im.convert("RGBA")), orig_mode
        return np.asarray(im.convert("RGB")), orig_mode


def save_png_unicode(arr: np.ndarray, out_path: Path) -> None:
    """Save lossless PNG safely on Windows with Unicode/Chinese paths."""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(arr).save(out_path, format="PNG", optimize=False, compress_level=6)


def estimate_alpha_core_params(
    rgb: np.ndarray,
    a_ch: np.ndarray,
    strict_interior: np.ndarray,
) -> tuple[float, float, bool]:
    """Auto-calibrate watermark color C and core opacity branch from local clean background."""
    clean_mask = (a_ch >= 254).astype(np.float32)
    bg_sum = cv2.boxFilter(rgb * clean_mask[:, :, None], -1, (17, 17), normalize=False)
    bg_cnt = cv2.boxFilter(clean_mask, -1, (17, 17), normalize=False)
    local_bg = bg_sum / np.maximum(1.0, bg_cnt[:, :, None])

    valid = strict_interior & (bg_cnt > 25)
    if valid.sum() < 20:
        return 255.0, 0.6000, True

    vals, cnts = np.unique(a_ch[strict_interior].astype(np.uint8), return_counts=True)
    a_mode = int(vals[np.argmax(cnts)])
    valid_mode = valid & (a_ch == a_mode)
    if valid_mode.sum() < 15:
        valid_mode = valid

    bg_v = local_bg[valid_mode]
    wm_v = rgb[valid_mode]

    disc = max(0.0, 4.0 * (a_mode / 255.0) - 3.0)
    sq = np.sqrt(disc)
    cand_ts = [0.5 * (1.0 + sq), 0.5 * (1.0 - sq)]
    if a_mode == 194:
        cand_ts.extend([0.6000, 0.4000])

    best_mae = 1e9
    best_cfg = (255.0, 0.6000, True)
    for C_cand in (255.0, 250.0, 245.0):
        for t_cand in cand_ts:
            if t_cand >= 0.92 or t_cand <= 0.05:
                continue
            rec = np.clip((wm_v - t_cand * C_cand) / (1.0 - t_cand), 0.0, 255.0)
            mae = float(np.mean(np.abs(rec - bg_v)))
            if mae < best_mae:
                best_mae = mae
                best_cfg = (C_cand, float(t_cand), t_cand >= 0.5)

    return best_cfg


def remove_via_alpha(arr: np.ndarray) -> tuple[np.ndarray, str, dict | None] | tuple[None, str, None]:
    """Mode A: Exact inverse alpha unblending when RGBA alpha holds RunningHub's signature."""
    if arr.ndim != 3 or arr.shape[2] != 4:
        return None, "无 Alpha 通道", None

    a_full = arr[:, :, 3]
    a_min = int(a_full.min())
    if a_min == 255:
        return None, "Alpha 全不透明", None
    if a_min < 175:
        return None, f"存在常规透明区域 (min_A={a_min})，非 RunningHub 水印特征", None

    ys, xs = np.where(a_full < 255)
    frac = len(ys) / float(a_full.size)
    if frac > 0.15 or len(ys) < 100:
        return None, f"Alpha 异常像素占比={frac:.3%}，跳过 Alpha 模式", None

    H, W = arr.shape[:2]
    pad = 24
    bx0, bx1 = int(xs.min()), int(xs.max()) + 1
    by0, by1 = int(ys.min()), int(ys.max()) + 1
    y0, y1 = max(0, by0 - pad), min(H, by1 + pad)
    x0, x1 = max(0, bx0 - pad), min(W, bx1 + pad)

    sub = arr[y0:y1, x0:x1, :].astype(np.float32)
    rgb = sub[:, :, :3]
    a_ch = sub[:, :, 3]

    disc = np.maximum(0.0, 4.0 * (a_ch / 255.0) - 3.0)
    sq = np.sqrt(disc)
    t_low = 0.5 * (1.0 - sq)
    t_high = 0.5 * (1.0 + sq)

    vals, cnts = np.unique(a_ch[a_ch < 235].astype(np.uint8), return_counts=True)
    a_mode = int(vals[np.argmax(cnts)]) if len(vals) > 0 else 194

    a_pad = np.pad(a_ch, 1, mode="edge")
    strict_interior = (
        (a_ch <= a_mode + 1)
        & (a_pad[:-2, 1:-1] <= a_mode + 2)
        & (a_pad[2:, 1:-1] <= a_mode + 2)
        & (a_pad[1:-1, :-2] <= a_mode + 2)
        & (a_pad[1:-1, 2:] <= a_mode + 2)
    )

    C_val, t_core_val, is_upper = estimate_alpha_core_params(rgb, a_ch, strict_interior)

    t_high_adj = np.clip(t_high, 0.0, 0.85)
    t_low_adj = np.clip(t_low, 0.0, 0.85)
    if is_upper:
        t_high_adj[a_ch == a_mode] = t_core_val
        t_interior = t_high_adj
    else:
        t_low_adj[a_ch == a_mode] = t_core_val
        t_interior = t_low_adj

    # Pass 1: Recover strict interior & build clean boundary reference via Telea
    work_rgb = rgb.copy()
    rec_strict = np.clip(
        (rgb - t_interior[:, :, None] * C_val) / np.maximum(1e-4, 1.0 - t_interior[:, :, None]),
        0.0,
        255.0,
    )
    work_rgb[strict_interior] = rec_strict[strict_interior]

    band_mask = ((a_ch < 254) & (~strict_interior)).astype(np.uint8)
    ref_bg = cv2.inpaint(work_rgb.astype(np.uint8), band_mask, 3, cv2.INPAINT_TELEA).astype(np.float32)

    cand = (a_ch <= max(a_mode, 194)) & (~strict_interior)
    dist_to_strict = cv2.distanceTransform((~strict_interior).astype(np.uint8), cv2.DIST_L2, 3)

    rec_c_low = np.clip(
        (rgb - t_low_adj[:, :, None] * C_val) / np.maximum(1e-4, 1.0 - t_low_adj[:, :, None]),
        0.0,
        255.0,
    )
    rec_c_high = np.clip(
        (rgb - t_high_adj[:, :, None] * C_val) / np.maximum(1e-4, 1.0 - t_high_adj[:, :, None]),
        0.0,
        255.0,
    )

    if is_upper:
        pick_high = (
            cand
            & (np.mean(np.abs(rec_c_high - ref_bg), axis=2) < np.mean(np.abs(rec_c_low - ref_bg), axis=2))
            & (dist_to_strict <= 1.5)
        )
        t_map = t_low_adj.copy()
        t_map[strict_interior] = t_interior[strict_interior]
        t_map[pick_high] = t_high_adj[pick_high]
    else:
        t_map = t_low_adj.copy()

    # Pass 2: 5x5 median consistency check on ambiguous cand pixels
    if is_upper and cand.any():
        rec_p1 = np.clip(
            (rgb - t_map[:, :, None] * C_val) / np.maximum(1e-4, 1.0 - t_map[:, :, None]),
            0.0,
            255.0,
        ).astype(np.uint8)
        med5 = cv2.medianBlur(rec_p1, 5).astype(np.float32)
        pick_high_2 = (
            cand
            & (np.mean(np.abs(rec_c_high - med5), axis=2) < np.mean(np.abs(rec_c_low - med5), axis=2))
            & (dist_to_strict <= 1.5)
        )
        t_map = t_low_adj.copy()
        t_map[strict_interior] = t_interior[strict_interior]
        t_map[pick_high_2] = t_high_adj[pick_high_2]
    else:
        pick_high_2 = np.zeros_like(cand, dtype=bool)

    # Pass 3: Sub-quantization refinement ONLY in flat-derivative trough A in [191, 193]
    t_refined = t_map.copy()
    flat_band = (a_ch <= 193) & (~strict_interior)
    for y, x in zip(*np.where(flat_band)):
        a_val = a_ch[y, x]
        lo_a = max(191.25, a_val - 0.52)
        hi_a = min(255.0, a_val + 0.52)
        d_lo = np.sqrt(max(0.0, 4.0 * (lo_a / 255.0) - 3.0))
        d_hi = np.sqrt(max(0.0, 4.0 * (hi_a / 255.0) - 3.0))
        if a_val == 191:
            t_min, t_max = 0.5 * (1.0 - d_hi), 0.5 * (1.0 + d_hi)
        elif pick_high_2[y, x]:
            t_min, t_max = 0.5 * (1.0 + d_lo), 0.5 * (1.0 + d_hi)
        else:
            t_min, t_max = 0.5 * (1.0 - d_hi), 0.5 * (1.0 - d_lo)
        denom = C_val - ref_bg[y, x]
        valid_c = denom > 30.0
        if np.any(valid_c):
            t_rgb = float(np.mean((rgb[y, x, valid_c] - ref_bg[y, x, valid_c]) / denom[valid_c]))
            t_refined[y, x] = np.clip(t_rgb, t_min, t_max)

    rec_final = np.clip(
        (rgb - t_refined[:, :, None] * C_val) / np.maximum(1e-4, 1.0 - t_refined[:, :, None]),
        0.0,
        255.0,
    ).astype(np.uint8)

    out_rgb = arr[:, :, :3].copy()
    out_rgb[y0:y1, x0:x1, :] = rec_final
    meta = {
        "mode": "Mode-A",
        "mode_label": "Alpha 无损逆混合",
        "bbox": [bx0, by0, bx1, by1],
        "opacity": round(float(t_core_val), 3),
        "color": int(C_val),
    }
    return out_rgb, f"Alpha 精确逆混合 (区域=[{bx0}:{bx1}, {by0}:{by1}], t={t_core_val:.2f}, C={C_val:.0f})", meta


def _grad_mag(gray: np.ndarray) -> np.ndarray:
    gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
    return cv2.magnitude(gx, gy)


def _search_roi_for_template(
    roi_gray: np.ndarray,
    tmpl: np.ndarray,
    short_side: int,
    img_w: int,
) -> tuple[float, int, int, int, int] | None:
    """Coarse-to-fine template matching on gradient magnitude inside roi_gray."""
    rh, rw = roi_gray.shape[:2]
    th0, tw0 = tmpl.shape[:2]
    aspect0 = th0 / float(tw0)

    proxy_scale = 1200.0 / float(max(rh, rw)) if max(rh, rw) > 1500 else 1.0
    roi_p = (
        cv2.resize(roi_gray, (int(rw * proxy_scale), int(rh * proxy_scale)), interpolation=cv2.INTER_AREA)
        if proxy_scale < 1.0
        else roi_gray
    )
    g_roi_p = _grad_mag(roi_p.astype(np.float32))
    prh, prw = roi_p.shape[:2]

    w_min = max(60, int(0.16 * short_side))
    w_max = min(rw - 4, int(max(0.48 * short_side, 0.38 * img_w)))
    if w_max <= w_min:
        return None

    best = None
    for w_full in np.unique(np.geomspace(w_min, w_max, 28).astype(int)):
        h_full = max(10, int(round(w_full * aspect0)))
        w_p = max(24, int(round(w_full * proxy_scale)))
        h_p = max(6, int(round(h_full * proxy_scale)))
        if w_p >= prw - 2 or h_p >= prh - 2:
            continue
        interp = cv2.INTER_AREA if w_p < tw0 else cv2.INTER_CUBIC
        t_p = cv2.resize(tmpl, (w_p, h_p), interpolation=interp)
        res = cv2.matchTemplate(g_roi_p, _grad_mag(t_p), cv2.TM_CCOEFF_NORMED)
        _, max_val, _, max_loc = cv2.minMaxLoc(res)
        if best is None or max_val > best[0]:
            best = (
                float(max_val),
                int(w_full),
                int(h_full),
                int(round(max_loc[0] / proxy_scale)),
                int(round(max_loc[1] / proxy_scale)),
            )

    if best is None or best[0] < 0.22:
        return None

    _, w_b, h_b, x_b, y_b = best
    pad_s = max(16, int(0.08 * w_b))
    sx0, sx1 = max(0, x_b - pad_s), min(rw, x_b + w_b + pad_s)
    sy0, sy1 = max(0, y_b - pad_s), min(rh, y_b + h_b + pad_s)
    g_sub = _grad_mag(roi_gray[sy0:sy1, sx0:sx1].astype(np.float32))

    fine_best = None
    for sw in np.linspace(0.93, 1.07, 15):
        w_f = int(round(w_b * sw))
        for sh_rel in (0.98, 1.0, 1.02):
            h_f = int(round(w_f * aspect0 * sh_rel))
            if w_f >= g_sub.shape[1] - 1 or h_f >= g_sub.shape[0] - 1 or w_f < 20 or h_f < 5:
                continue
            interp = cv2.INTER_AREA if w_f < tw0 else cv2.INTER_CUBIC
            t_f = cv2.resize(tmpl, (w_f, h_f), interpolation=interp)
            res = cv2.matchTemplate(g_sub, _grad_mag(t_f), cv2.TM_CCOEFF_NORMED)
            _, max_val, _, max_loc = cv2.minMaxLoc(res)
            if fine_best is None or max_val > fine_best[0]:
                fine_best = (float(max_val), w_f, h_f, sx0 + max_loc[0], sy0 + max_loc[1])

    return fine_best


def remove_via_template(arr: np.ndarray, tmpl: np.ndarray) -> tuple[np.ndarray, str, dict | None] | tuple[None, str, None]:
    """Mode B: Template-matched sub-pixel alignment + hybrid unblending for JPG/WebP/RGB PNGs."""
    rgb_full = arr[:, :, :3]
    H, W = rgb_full.shape[:2]
    short_side = min(H, W)
    gray_full = cv2.cvtColor(rgb_full, cv2.COLOR_RGB2GRAY)

    roi_h = min(H, max(120, int(0.28 * H)))
    roi_w = min(W, max(300, int(0.65 * W)))
    corners = [
        ("TL", 0, roi_h, 0, roi_w),
        ("TR", 0, roi_h, max(0, W - roi_w), W),
        ("BL", max(0, H - roi_h), H, 0, roi_w),
        ("BR", max(0, H - roi_h), H, max(0, W - roi_w), W),
    ]

    match_res = None
    for cname, cy0, cy1, cx0, cx1 in corners:
        hit = _search_roi_for_template(gray_full[cy0:cy1, cx0:cx1], tmpl, short_side, W)
        if hit is not None and hit[0] >= 0.30:
            corr, w_f, h_f, lx, ly = hit
            match_res = (cname, corr, w_f, h_f, cx0 + lx, cy0 + ly)
            break

    if match_res is None:
        return None, "未检测到 RunningHub AI 水印", None

    cname, fine_corr, w_f, h_f, x_f, y_f = match_res
    th0, tw0 = tmpl.shape[:2]

    pad = max(16, int(0.12 * h_f))
    px0, py0 = max(0, x_f - pad), max(0, y_f - pad)
    px1, py1 = min(W, x_f + w_f + pad), min(H, y_f + h_f + pad)
    pw, ph = px1 - px0, py1 - py0
    patch_rgb = rgb_full[py0:py1, px0:px1].astype(np.float32)

    ox, oy = float(x_f - px0), float(y_f - py0)
    sx, sy = float(w_f) / float(tw0), float(h_f) / float(th0)

    def render_tmpl(ox_v: float, oy_v: float, sx_v: float, sy_v: float, sigma: float = 0.0) -> np.ndarray:
        M = np.float32([[sx_v, 0.0, ox_v], [0.0, sy_v, oy_v]])
        interp = cv2.INTER_AREA if sx_v < 0.9 else cv2.INTER_CUBIC
        warped = cv2.warpAffine(tmpl, M, (pw, ph), flags=interp, borderValue=0.0)
        if sigma > 0.05:
            k = max(3, int(round(sigma * 4)) * 2 + 1)
            warped = cv2.GaussianBlur(warped, (k, k), sigma)
        return np.clip(warped, 0.0, 1.0)

    m_init = render_tmpl(ox, oy, sx, sy)
    dil_k = max(5, int(round(max(5, h_f * 0.04))) | 1)
    wm_dil = cv2.dilate((m_init > 0.02).astype(np.uint8), np.ones((dil_k, dil_k), np.uint8))
    ref_bg = cv2.inpaint(patch_rgb.astype(np.uint8), wm_dil, 4, cv2.INPAINT_TELEA).astype(np.float32)

    # Verify white semi-transparent watermark contrast against local background
    fg_init = m_init >= 0.75
    if fg_init.sum() < 15:
        fg_init = m_init >= 0.45
    if fg_init.sum() < 10:
        return None, "有效采样像素不足", None

    step_mean = float(np.mean(patch_rgb[fg_init] - ref_bg[fg_init]))
    if step_mean < 4.0:
        return None, f"亮度特征不符 RunningHub 白色半透明水印 (step={step_mean:.1f})", None

    # Coordinate descent on (ox, oy, sx, sy, sigma) down to 0.1px
    obs_t = np.mean((patch_rgb - ref_bg) / np.maximum(35.0, 255.0 - ref_bg), axis=2)
    eval_zone = cv2.dilate((m_init > 0.02).astype(np.uint8), np.ones((7, 7), np.uint8)) > 0

    def eval_loss(ox_v: float, oy_v: float, sx_v: float, sy_v: float, sig_v: float) -> tuple[float, float]:
        m = render_tmpl(ox_v, oy_v, sx_v, sy_v, sig_v)
        mz, oz = m[eval_zone], obs_t[eval_zone]
        tc = float(np.clip(np.sum(mz * oz) / (np.sum(mz * mz) + 1e-8), 0.20, 0.85))
        return float(np.mean((oz - tc * mz) ** 2)), tc

    sig = 0.5 if w_f < 600 else 0.2
    cur_loss, t_core = eval_loss(ox, oy, sx, sy, sig)

    for step_px in (1.5, 0.6, 0.25, 0.1):
        for _ in range(5):
            changed = False
            for d in (-step_px, step_px):
                l, tc = eval_loss(ox + d, oy, sx, sy, sig)
                if l < cur_loss - 1e-7:
                    ox += d; cur_loss, t_core = l, tc; changed = True
                l, tc = eval_loss(ox, oy + d, sx, sy, sig)
                if l < cur_loss - 1e-7:
                    oy += d; cur_loss, t_core = l, tc; changed = True
                dsx, dsy = (d * 1.5) / float(tw0), (d * 1.5) / float(th0)
                l, tc = eval_loss(ox, oy, sx + dsx, sy, sig)
                if l < cur_loss - 1e-7:
                    sx += dsx; cur_loss, t_core = l, tc; changed = True
                l, tc = eval_loss(ox, oy, sx, sy + dsy, sig)
                if l < cur_loss - 1e-7:
                    sy += dsy; cur_loss, t_core = l, tc; changed = True
            for ds in (-0.15, 0.15):
                ns = float(np.clip(sig + ds, 0.0, 1.5))
                if abs(ns - sig) > 1e-4:
                    l, tc = eval_loss(ox, oy, sx, sy, ns)
                    if l < cur_loss - 1e-7:
                        sig = ns; cur_loss, t_core = l, tc; changed = True
            if not changed:
                break

    aligned = render_tmpl(ox, oy, sx, sy, sig)
    if abs(t_core - 0.60) <= 0.035:
        t_core = 0.60

    if w_f >= 650:
        # High-res RGB/JPG: mathematically unblend eroded stroke interior, Telea-bridge thin DCT border
        t_eff = np.clip(t_core * aligned, 0.0, 0.86)
        rec = np.clip((patch_rgb - t_eff[:, :, None] * 255.0) / (1.0 - t_eff[:, :, None]), 0.0, 255.0).astype(np.uint8)
        core_eroded = cv2.erode((aligned >= 0.94).astype(np.uint8), np.ones((5, 5), np.uint8)) > 0
        outer_dil = cv2.dilate((aligned > 0.02).astype(np.uint8), np.ones((5, 5), np.uint8)) > 0
        ring_mask = outer_dil & (~core_eroded)
        cleaned_patch = cv2.inpaint(rec, ring_mask.astype(np.uint8), 3, cv2.INPAINT_TELEA)
        method_tag = "核心逆混合+边界桥接"
    else:
        # Medium/low-res compressed images: strokes are 1.5-6px wide, clean dilated Telea avoids DCT halo
        k_s1 = max(3, int(round(max(3, w_f / 260.0))) | 1)
        mask_s1 = cv2.dilate((aligned > 0.02).astype(np.uint8), np.ones((k_s1, k_s1), np.uint8))
        cleaned_patch = cv2.inpaint(patch_rgb.astype(np.uint8), mask_s1, max(3, k_s1), cv2.INPAINT_TELEA)
        method_tag = "细笔画自适应修补"

    out_rgb = rgb_full.copy()
    out_rgb[py0:py1, px0:px1, :] = cleaned_patch
    meta = {
        "mode": "Mode-B",
        "mode_label": f"模板匹配 · {method_tag}",
        "bbox": [int(x_f), int(y_f), int(x_f + w_f), int(y_f + h_f)],
        "opacity": round(float(t_core), 3),
        "score": round(float(fine_corr), 3),
        "corner": cname,
    }
    return out_rgb, f"模板匹配-{method_tag} ({cname}, 相关度={fine_corr:.2f}, 区域=[{x_f}:{x_f+w_f}, {y_f}:{y_f+h_f}], t={t_core:.2f})", meta


def collect_inputs(raw_args: list[str]) -> list[Path]:
    files: list[Path] = []
    seen = set()
    for arg in raw_args:
        p = Path(arg.strip().strip('"'))
        if not p.exists():
            continue
        if p.is_dir():
            for root, _, fns in os.walk(p):
                for fn in sorted(fns):
                    fp = Path(root) / fn
                    if fp.suffix.lower() in IMAGE_EXTS and not fp.stem.endswith(SKIP_SUFFIX):
                        rp = fp.resolve()
                        if rp not in seen:
                            seen.add(rp)
                            files.append(fp)
        elif p.is_file() and p.suffix.lower() in IMAGE_EXTS:
            rp = p.resolve()
            if rp not in seen:
                seen.add(rp)
                files.append(p)
    return files


def pick_files_gui() -> list[str]:
    try:
        import tkinter as tk
        from tkinter import filedialog

        root = tk.Tk()
        root.withdraw()
        root.attributes("-topmost", True)
        selected = filedialog.askopenfilenames(
            title="选择需要去除 RunningHub 水印的图片（可多选）",
            filetypes=[
                ("图片文件", "*.png;*.jpg;*.jpeg;*.webp;*.bmp;*.tif;*.tiff"),
                ("所有文件", "*.*"),
            ],
        )
        root.destroy()
        return list(selected)
    except Exception:
        return []


def process_file_with_result(path: Path, tmpl_img: np.ndarray | None, out_dir: Path | None = None) -> dict:
    """Process one image file and return structured result dictionary for both CLI and GUI."""
    t0 = time.perf_counter()
    arr, _ = load_image_unicode(path)

    cleaned, detail, meta = remove_via_alpha(arr)
    mode_used = "Mode-A"
    if cleaned is None:
        if tmpl_img is None:
            return {"status": "skipped", "detail": f"{detail}，且未找到模板文件 rh_template.png", "elapsed": time.perf_counter() - t0}
        cleaned, detail, meta = remove_via_template(arr, tmpl_img)
        mode_used = "Mode-B"

    if cleaned is None:
        return {"status": "skipped", "detail": detail, "elapsed": time.perf_counter() - t0}

    if arr.ndim == 3 and arr.shape[2] == 4 and int(arr[:, :, 3].min()) < 175:
        out_arr = np.dstack([cleaned, arr[:, :, 3]])
    else:
        out_arr = cleaned

    target_dir = out_dir if out_dir is not None else path.parent
    out_path = target_dir / f"{path.stem}{SKIP_SUFFIX}.png"
    save_png_unicode(out_arr, out_path)

    dt = time.perf_counter() - t0
    size_mb = out_path.stat().st_size / (1024 * 1024)
    return {
        "status": "done",
        "mode": mode_used,
        "detail": detail,
        "meta": meta or {},
        "out_path": out_path,
        "out_size_mb": size_mb,
        "elapsed": dt,
        "orig_arr": arr[:, :, :3],
        "clean_arr": cleaned,
    }


def process_file(path: Path, tmpl_img: np.ndarray | None, out_dir: Path | None = None) -> bool:
    res = process_file_with_result(path, tmpl_img, out_dir)
    if res["status"] != "done":
        print(f"[跳过] {path.name}: {res['detail']}")
        return False
    out_path: Path = res["out_path"]
    print(f"[成功] {path.name} -> {out_path.name} ({res['out_size_mb']:.2f} MB, {res['elapsed']:.2f}s) | {res['mode']}: {res['detail']}")
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description="RunningHub AI 自动去水印工具")
    parser.add_argument("paths", nargs="*", help="待处理的图片或文件夹路径")
    parser.add_argument("--out-dir", type=str, default=None, help="可选输出目录（默认保存在原图同目录）")
    args = parser.parse_args()

    raw_paths = args.paths
    if not raw_paths:
        raw_paths = pick_files_gui()
        if not raw_paths:
            print("未选择任何图片。用法：直接把图片（或文件夹）拖放到 去RunningHub水印.bat 上即可。")
            return 0

    files = collect_inputs(raw_paths)
    if not files:
        print("未找到有效图片文件（支持 png/jpg/jpeg/webp/bmp/tif）。")
        return 1

    tmpl_img = None
    if TEMPLATE_PATH.exists():
        tmpl_img = np.asarray(Image.open(TEMPLATE_PATH).convert("L"), dtype=np.float32) / 255.0

    out_dir = Path(args.out_dir) if args.out_dir else None
    print(f"共检测到 {len(files)} 张待处理图片，开始处理...\n")

    ok = 0
    for f in files:
        try:
            if process_file(f, tmpl_img, out_dir):
                ok += 1
        except Exception as exc:
            print(f"[失败] {f.name}: {exc}")

    print(f"\n处理完成：成功 {ok} 张 / 共 {len(files)} 张。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
