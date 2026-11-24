#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RunningHub Clear Studio — Native Desktop GUI Panel (pywebview + Edge WebView2)
"""

import ctypes
import io
import json
import os
import subprocess
import sys
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import cv2
import numpy as np
import webview
from PIL import Image
from webview.dom import DOMEventHandler

# Ensure resource/module lookup works both from source and PyInstaller frozen EXE
if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
    APP_DIR = Path(sys._MEIPASS)
else:
    APP_DIR = Path(__file__).resolve().parent
if str(APP_DIR) not in sys.path:
    sys.path.insert(0, str(APP_DIR))

from remove_rh_watermark import (
    IMAGE_EXTS,
    SKIP_SUFFIX,
    TEMPLATE_PATH,
    load_image_unicode,
    process_file_with_result,
)

WINDOW_TITLE = "RunningHub 无损去水印工作台"
UI_HTML_PATH = APP_DIR / "panel_ui.html"
ICON_PATH = APP_DIR / "app.ico"


def _encode_image_bytes(arr_rgb: np.ndarray, max_side: int | None = None, as_jpeg: bool = False) -> bytes:
    """Resize if needed and encode RGB numpy array to PNG/JPEG bytes for fast UI preview."""
    h, w = arr_rgb.shape[:2]
    work = arr_rgb
    if max_side is not None and max(h, w) > max_side:
        scale = max_side / float(max(h, w))
        nw, nh = max(1, int(round(w * scale))), max(1, int(round(h * scale)))
        work = cv2.resize(arr_rgb, (nw, nh), interpolation=cv2.INTER_AREA)

    im = Image.fromarray(work)
    buf = io.BytesIO()
    if as_jpeg:
        im.save(buf, format="JPEG", quality=93, optimize=False)
    else:
        im.save(buf, format="PNG", optimize=False, compress_level=1)
    return buf.getvalue()


def _make_zoom_crop(arr_rgb: np.ndarray, bbox: list[int] | None) -> np.ndarray:
    """Extract crisp watermark-area zoom crop for before/after wipe comparison."""
    h, w = arr_rgb.shape[:2]
    if bbox and len(bbox) == 4:
        bx0, by0, bx1, by1 = bbox
        pad_x = max(48, int((by1 - by0) * 0.45))
        pad_y = max(36, int((by1 - by0) * 0.35))
        x0, x1 = max(0, bx0 - pad_x), min(w, bx1 + pad_x)
        y0, y1 = max(0, by0 - pad_y), min(h, by1 + pad_y)
    else:
        x0, y0 = 0, 0
        x1 = min(w, max(480, int(w * 0.42)))
        y1 = min(h, max(160, int(h * 0.16)))

    crop = arr_rgb[y0:y1, x0:x1]
    cw, ch = crop.shape[1], crop.shape[0]
    if cw < 560:
        crop = cv2.resize(crop, (cw * 2, ch * 2), interpolation=cv2.INTER_CUBIC)
    elif cw > 1500:
        scale = 1500.0 / float(cw)
        crop = cv2.resize(crop, (1500, max(1, int(round(ch * scale)))), interpolation=cv2.INTER_AREA)
    return crop


def _apply_win32_dark_chrome(title: str, ico_path: Path) -> None:
    """Apply Windows 11 dark titlebar matching #090B10 and bind custom app.ico icon."""
    if sys.platform != "win32":
        return
    try:
        user32 = ctypes.windll.user32
        dwmapi = ctypes.windll.dwmapi
        hwnd = user32.FindWindowW(None, title)
        if not hwnd:
            return

        # DWMWA_USE_IMMERSIVE_DARK_MODE = 20, DWMWA_CAPTION_COLOR = 35
        dark = ctypes.c_int(1)
        dwmapi.DwmSetWindowAttribute(hwnd, 20, ctypes.byref(dark), ctypes.sizeof(dark))
        caption_bgr = ctypes.c_int(0x00100B09)  # #090B10 in COLORREF (0x00BBGGRR)
        dwmapi.DwmSetWindowAttribute(hwnd, 35, ctypes.byref(caption_bgr), ctypes.sizeof(caption_bgr))

        if ico_path.exists():
            # IMAGE_ICON = 1, LR_LOADFROMFILE = 0x0010, WM_SETICON = 0x0080
            hicon_big = user32.LoadImageW(None, str(ico_path), 1, 48, 48, 0x0010)
            hicon_small = user32.LoadImageW(None, str(ico_path), 1, 16, 16, 0x0010)
            if hicon_big:
                user32.SendMessageW(hwnd, 0x0080, 1, hicon_big)
            if hicon_small:
                user32.SendMessageW(hwnd, 0x0080, 0, hicon_small)
    except Exception:
        pass


class StudioState:
    def __init__(self, initial_paths: list[str] | None = None):
        self.lock = threading.RLock()
        self.items: list[dict] = []
        self.items_by_id: dict[str, dict] = {}
        self.preview_cache: dict[tuple[str, str], tuple[bytes, str]] = {}
        self.out_dir: Path | None = None
        self.auto_run: bool = True
        self.skip_done: bool = True
        self.is_running: bool = False
        self._window: webview.Window | None = None
        self.tmpl_img: np.ndarray | None = None
        if TEMPLATE_PATH.exists():
            self.tmpl_img = np.asarray(Image.open(TEMPLATE_PATH).convert("L"), dtype=np.float32) / 255.0
        self._initial_paths = initial_paths or []

    def push_ui(self, toast: str | None = None, auto_select_id: str | None = None) -> None:
        if self._window is None:
            return
        with self.lock:
            payload = {
                "items": [
                    {
                        "id": it["id"],
                        "path": str(it["path"]),
                        "name": it["path"].name,
                        "dims": it["dims"],
                        "size_mb": it["size_mb"],
                        "status": it["status"],
                        "mode": it.get("mode"),
                        "detail": it.get("detail", ""),
                        "elapsed": it.get("elapsed"),
                        "version": it.get("version", 1),
                    }
                    for it in self.items
                ],
                "out_dir": str(self.out_dir) if self.out_dir else None,
                "is_running": self.is_running,
                "toast": toast,
                "auto_select_id": auto_select_id,
            }
        try:
            self._window.evaluate_js(f"window.__syncState({json.dumps(payload, ensure_ascii=False)})")
        except Exception:
            pass

    def enqueue_paths(self, raw_paths: list[str]) -> int:
        expanded: list[Path] = []
        seen_paths = {str(it["path"].resolve()) for it in self.items}

        for raw in raw_paths:
            p = Path(str(raw).strip().strip('"'))
            if not p.exists():
                continue
            if p.is_dir():
                for root, _, fns in os.walk(p):
                    for fn in sorted(fns):
                        fp = Path(root) / fn
                        if fp.suffix.lower() in IMAGE_EXTS:
                            expanded.append(fp)
            elif p.is_file() and p.suffix.lower() in IMAGE_EXTS:
                expanded.append(p)

        added_items: list[dict] = []
        skipped_existing = 0
        for fp in expanded:
            if self.skip_done and fp.stem.endswith(SKIP_SUFFIX):
                skipped_existing += 1
                continue
            rp = str(fp.resolve())
            if rp in seen_paths:
                continue
            seen_paths.add(rp)
            try:
                sz_mb = fp.stat().st_size / (1024.0 * 1024.0)
                with Image.open(fp) as im:
                    w, h = im.size
                dims = f"{w}×{h}"
            except Exception:
                sz_mb = 0.0
                dims = "--"

            item_id = uuid.uuid4().hex[:12]
            item = {
                "id": item_id,
                "path": fp,
                "dims": dims,
                "size_mb": sz_mb,
                "status": "queued",
                "mode": None,
                "detail": "等待处理",
                "bbox": None,
                "out_path": None,
                "elapsed": None,
                "version": 1,
            }
            added_items.append(item)

        if not added_items:
            if skipped_existing > 0:
                self.push_ui(toast=f"已自动过滤 {skipped_existing} 张 _去水印 成品图")
            return 0

        with self.lock:
            self.items.extend(added_items)
            for it in added_items:
                self.items_by_id[it["id"]] = it

        first_id = added_items[0]["id"]
        self.push_ui(toast=f"已加入 {len(added_items)} 张图片", auto_select_id=first_id)

        if self.auto_run:
            self.start_worker()
        return len(added_items)

    def start_worker(self) -> None:
        with self.lock:
            if self.is_running:
                return
            has_queued = any(it["status"] == "queued" for it in self.items)
            if not has_queued:
                return
            self.is_running = True

        self.push_ui()
        t = threading.Thread(target=self._worker_loop, daemon=True)
        t.start()

    def _worker_loop(self) -> None:
        done_count = 0
        last_done_id = None
        try:
            while True:
                next_item = None
                with self.lock:
                    for it in self.items:
                        if it["status"] == "queued":
                            next_item = it
                            it["status"] = "processing"
                            it["detail"] = "正在检测并去除水印..."
                            break
                    out_dir_snap = self.out_dir

                if next_item is None:
                    break

                self.push_ui(auto_select_id=next_item["id"])
                try:
                    res = process_file_with_result(next_item["path"], self.tmpl_img, out_dir_snap)
                    if res["status"] == "done":
                        meta = res["meta"]
                        bbox = meta.get("bbox")
                        orig_arr = res["orig_arr"]
                        clean_arr = res["clean_arr"]

                        # Pre-build compact preview caches so huge PNG arrays are freed right away
                        o_fit = _encode_image_bytes(orig_arr, max_side=1600, as_jpeg=True)
                        c_fit = _encode_image_bytes(clean_arr, max_side=1600, as_jpeg=True)
                        o_zoom = _encode_image_bytes(_make_zoom_crop(orig_arr, bbox), as_jpeg=False)
                        c_zoom = _encode_image_bytes(_make_zoom_crop(clean_arr, bbox), as_jpeg=False)

                        with self.lock:
                            self.preview_cache[(next_item["id"], "orig_fit")] = (o_fit, "image/jpeg")
                            self.preview_cache[(next_item["id"], "clean_fit")] = (c_fit, "image/jpeg")
                            self.preview_cache[(next_item["id"], "orig_zoom")] = (o_zoom, "image/png")
                            self.preview_cache[(next_item["id"], "clean_zoom")] = (c_zoom, "image/png")
                            next_item["status"] = "done"
                            next_item["mode"] = res["mode"]
                            next_item["detail"] = res["detail"]
                            next_item["bbox"] = bbox
                            next_item["out_path"] = res["out_path"]
                            next_item["elapsed"] = res["elapsed"]
                            next_item["version"] += 1
                        done_count += 1
                        last_done_id = next_item["id"]
                    else:
                        with self.lock:
                            next_item["status"] = "skipped"
                            next_item["detail"] = res["detail"]
                            next_item["elapsed"] = res["elapsed"]
                            next_item["version"] += 1
                except Exception as exc:
                    with self.lock:
                        next_item["status"] = "error"
                        next_item["detail"] = f"处理失败: {exc}"
                        next_item["version"] += 1

                self.push_ui(auto_select_id=last_done_id or next_item["id"])
        finally:
            with self.lock:
                self.is_running = False
            msg = f"全部处理完成（成功去除 {done_count} 张）" if done_count > 0 else "队列处理完毕"
            self.push_ui(toast=msg, auto_select_id=last_done_id)

    def get_preview(self, item_id: str, kind: str) -> tuple[bytes, str] | None:
        with self.lock:
            cached = self.preview_cache.get((item_id, kind))
            if cached is not None:
                return cached
            item = self.items_by_id.get(item_id)
            if item is None:
                return None
            src_path = item["path"]
            out_path = item.get("out_path")
            bbox = item.get("bbox")

        # Generate lazily outside lock
        try:
            if kind == "thumb":
                with Image.open(src_path) as im:
                    im = im.convert("RGB")
                    im.thumbnail((96, 96), Image.Resampling.BILINEAR)
                    buf = io.BytesIO()
                    im.save(buf, format="JPEG", quality=85)
                    data = (buf.getvalue(), "image/jpeg")
            elif kind in ("orig_fit", "orig_zoom"):
                arr, _ = load_image_unicode(src_path)
                rgb = arr[:, :, :3]
                if kind == "orig_fit":
                    data = (_encode_image_bytes(rgb, max_side=1600, as_jpeg=True), "image/jpeg")
                else:
                    data = (_encode_image_bytes(_make_zoom_crop(rgb, bbox), as_jpeg=False), "image/png")
            elif kind in ("clean_fit", "clean_zoom") and out_path and Path(out_path).exists():
                arr, _ = load_image_unicode(Path(out_path))
                rgb = arr[:, :, :3]
                if kind == "clean_fit":
                    data = (_encode_image_bytes(rgb, max_side=1600, as_jpeg=True), "image/jpeg")
                else:
                    data = (_encode_image_bytes(_make_zoom_crop(rgb, bbox), as_jpeg=False), "image/png")
            else:
                return None

            with self.lock:
                self.preview_cache[(item_id, kind)] = data
            return data
        except Exception:
            return None


class PanelApi:
    def __init__(self, state: StudioState):
        # Prefix with '_' so pywebview never introspects state or window.native
        self._state = state

    def request_initial_state(self) -> None:
        if self._state._initial_paths:
            paths = list(self._state._initial_paths)
            self._state._initial_paths = []
            self._state.enqueue_paths(paths)
        else:
            self._state.push_ui()

    def pick_files(self) -> None:
        if not self._state._window:
            return
        res = self._state._window.create_file_dialog(
            webview.FileDialog.OPEN,
            allow_multiple=True,
            file_types=("图片文件 (*.png;*.jpg;*.jpeg;*.webp;*.bmp;*.tif;*.tiff)", "所有文件 (*.*)"),
        )
        if res:
            self._state.enqueue_paths(list(res))

    def pick_folder(self) -> None:
        if not self._state._window:
            return
        res = self._state._window.create_file_dialog(webview.FileDialog.FOLDER)
        if res:
            self._state.enqueue_paths(list(res))

    def pick_out_dir(self) -> None:
        if not self._state._window:
            return
        res = self._state._window.create_file_dialog(webview.FileDialog.FOLDER)
        if res:
            with self._state.lock:
                self._state.out_dir = Path(res[0])
            self._state.push_ui(toast=f"已设置统一输出目录：{res[0]}")

    def set_out_dir(self, path_or_none: str | None) -> None:
        with self._state.lock:
            self._state.out_dir = Path(path_or_none) if path_or_none else None
        self._state.push_ui(toast="已切换为保存至原图同目录")

    def set_options(self, auto_run: bool, skip_done: bool) -> None:
        with self._state.lock:
            self._state.auto_run = bool(auto_run)
            self._state.skip_done = bool(skip_done)

    def start_all(self) -> None:
        self._state.start_worker()

    def clear_queue(self) -> None:
        with self._state.lock:
            if self._state.is_running:
                self._state.push_ui(toast="正在处理中，请稍候完成后再清空")
                return
            self._state.items.clear()
            self._state.items_by_id.clear()
            self._state.preview_cache.clear()
        self._state.push_ui(toast="已清空列表")

    def remove_item(self, item_id: str) -> None:
        with self._state.lock:
            it = self._state.items_by_id.get(item_id)
            if not it or it["status"] == "processing":
                return
            self._state.items = [x for x in self._state.items if x["id"] != item_id]
            self._state.items_by_id.pop(item_id, None)
            for k in [k for k in self._state.preview_cache if k[0] == item_id]:
                self._state.preview_cache.pop(k, None)
        self._state.push_ui()

    def open_output_file(self, item_id: str) -> None:
        with self._state.lock:
            it = self._state.items_by_id.get(item_id)
            target = it.get("out_path") if it else None
        if target and Path(target).exists():
            os.startfile(str(target))

    def open_folder(self, item_id: str) -> None:
        with self._state.lock:
            it = self._state.items_by_id.get(item_id)
            if not it:
                return
            target = it.get("out_path") or it["path"]
        p = Path(target)
        if p.exists():
            subprocess.Popen(["explorer", "/select,", str(p)])


def start_local_server(state: StudioState) -> tuple[ThreadingHTTPServer, int]:
    html_bytes = UI_HTML_PATH.read_bytes()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            return

        def do_GET(self):
            parsed = urlparse(self.path)
            if parsed.path in ("/", "/index.html"):
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(html_bytes)))
                self.end_headers()
                self.wfile.write(html_bytes)
                return

            if parsed.path == "/api/preview":
                qs = parse_qs(parsed.query)
                item_id = qs.get("id", [""])[0]
                kind = qs.get("kind", [""])[0]
                res = state.get_preview(item_id, kind)
                if res is None:
                    self.send_response(404)
                    self.end_headers()
                    return
                data, mime = res
                self.send_response(200)
                self.send_header("Content-Type", mime)
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return

            self.send_response(404)
            self.end_headers()

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    port = server.server_address[1]
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    return server, port


def main() -> int:
    initial_args = [a for a in sys.argv[1:] if a.strip()]
    state = StudioState(initial_paths=initial_args)
    api = PanelApi(state)
    server, port = start_local_server(state)

    window = webview.create_window(
        title=WINDOW_TITLE,
        url=f"http://127.0.0.1:{port}/",
        js_api=api,
        width=1260,
        height=800,
        min_size=(960, 640),
        background_color="#090B10",
        text_select=False,
    )
    state._window = window

    def on_drop(e):
        files = (e or {}).get("dataTransfer", {}).get("files", [])
        paths = [f["pywebviewFullPath"] for f in files if isinstance(f, dict) and f.get("pywebviewFullPath")]
        if paths:
            state.enqueue_paths(paths)

    def on_ready(win):
        win.events.loaded.wait(10)
        _apply_win32_dark_chrome(WINDOW_TITLE, ICON_PATH)
        try:
            win.dom.document.events.dragover += DOMEventHandler(lambda e: None, True, False)
            win.dom.document.events.drop += DOMEventHandler(on_drop, True, False)
        except Exception:
            pass
        if state._initial_paths:
            paths = list(state._initial_paths)
            state._initial_paths = []
            state.enqueue_paths(paths)
        else:
            state.push_ui()

    def on_closed():
        # Shutdown server asynchronously so WinForms FormClosed never blocks or raises WinError 10038
        threading.Thread(target=server.shutdown, daemon=True).start()

    window.events.closed += on_closed

    webview.start(on_ready, window, debug=False, icon=str(ICON_PATH) if ICON_PATH.exists() else None)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
