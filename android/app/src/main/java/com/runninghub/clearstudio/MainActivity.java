package com.runninghub.clearstudio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Insets;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String VIRTUAL_SCHEME = "http";
    private static final String VIRTUAL_HOST = "appassets.local";
    private static final String ENTRY_URL = VIRTUAL_SCHEME + "://" + VIRTUAL_HOST + "/www/index.html";
    private static final String APP_VERSION = "1.0.0";
    private static final int PICK_IMAGES_CODE = 1001;
    private static final int WRITE_PERM_CODE = 2001;
    private static final int BG_DARK = 0xFF090B10;

    private static final class QueueItem {
        final String id;
        final String name;
        final String stem;
        volatile String status = "queued"; // queued, processing, done, skipped, error
        volatile String mode = "";
        volatile String modeLabel = "";
        volatile String detail = "等待处理";
        volatile int width = 0;
        volatile int height = 0;
        volatile double opacity = 0.0;
        volatile double score = 0.0;
        volatile String corner = "";
        volatile double elapsedSec = 0.0;
        volatile double outSizeMb = 0.0;
        volatile String outputName = "";
        volatile String outputPath = "";
        volatile boolean hasZoom = false;
        volatile boolean hasFull = false;
        volatile boolean savedToGallery = false;
        volatile String pendingExt = "";

        QueueItem(String id, String name, String stem) {
            this.id = id;
            this.name = name;
            this.stem = stem;
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService workerPool = Executors.newSingleThreadExecutor();
    private final Object stateLock = new Object();
    private final Map<String, QueueItem> items = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();
    private volatile String selectedId = null;

    private FrameLayout rootLayout;
    private WebView webView;
    private volatile boolean pageLoaded = false;
    private volatile String pendingSaveActionId = null; // null = none, "__ALL__" = saveAll

    private int lastSentTopCss = -1;
    private int lastSentBottomCss = -1;

    private float[] tmplData = null;
    private int tmplW = 0;
    private int tmplH = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setupEdgeToEdge();
        cleanupOldCache();

        rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.setBackgroundColor(BG_DARK);

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setBackgroundColor(BG_DARK);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        configureWebView(webView);
        rootLayout.addView(webView);
        setContentView(rootLayout);
        setupWindowInsets();

        handleIncomingIntent(getIntent());
        webView.loadUrl(ENTRY_URL);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onDestroy() {
        workerPool.shutdownNow();
        if (webView != null) {
            webView.stopLoading();
            webView.removeJavascriptInterface("AndroidBridge");
            webView.setWebChromeClient(null);
            webView.setWebViewClient(new WebViewClient());
            if (rootLayout != null) {
                rootLayout.removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        super.onBackPressed();
    }

    @SuppressWarnings("deprecation")
    private void setupEdgeToEdge() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController ctrl = window.getInsetsController();
            if (ctrl != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                ctrl.setSystemBarsAppearance(0, mask);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @SuppressWarnings("deprecation")
    private void setupWindowInsets() {
        rootLayout.setOnApplyWindowInsetsListener((v, insets) -> {
            int topPx = 0;
            int bottomPx = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                topPx = bars.top;
                bottomPx = bars.bottom;
            } else {
                topPx = insets.getSystemWindowInsetTop();
                bottomPx = insets.getSystemWindowInsetBottom();
            }
            float density = getResources().getDisplayMetrics().density;
            int topCss = Math.round(topPx / Math.max(1.0f, density));
            int bottomCss = Math.round(bottomPx / Math.max(1.0f, density));
            lastSentTopCss = topCss;
            lastSentBottomCss = bottomCss;
            pushSafeInsetsToWeb(topCss, bottomCss);
            return insets;
        });
    }

    private void pushSafeInsetsToWeb(int topCss, int bottomCss) {
        if (!pageLoaded || webView == null) return;
        final String js = "document.documentElement.style.setProperty('--safe-top','"
                + topCss + "px');document.documentElement.style.setProperty('--safe-bottom','"
                + bottomCss + "px');";
        mainHandler.post(() -> {
            if (webView != null) {
                webView.evaluateJavascript(js, null);
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        wv.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");
        wv.setWebChromeClient(new WebChromeClient());
        wv.setWebViewClient(new LocalWebViewClient(this));
    }

    private static final class LocalWebViewClient extends WebViewClient {
        private final MainActivity activity;

        LocalWebViewClient(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request != null ? request.getUrl() : null;
            if (uri != null && VIRTUAL_HOST.equalsIgnoreCase(uri.getHost())) {
                return activity.serveVirtualRequest(uri);
            }
            return makeStatusResponse(403, "Offline");
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request != null ? request.getUrl() : null;
            if (uri != null && VIRTUAL_SCHEME.equalsIgnoreCase(uri.getScheme())
                    && VIRTUAL_HOST.equalsIgnoreCase(uri.getHost())) {
                return false;
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            activity.pageLoaded = true;
            if (activity.lastSentTopCss >= 0) {
                activity.pushSafeInsetsToWeb(activity.lastSentTopCss, activity.lastSentBottomCss);
            }
            activity.pushStateToWeb();
        }
    }

    private WebResourceResponse serveVirtualRequest(Uri uri) {
        String path = uri.getPath();
        if (path == null) return makeStatusResponse(404, "Not Found");
        if (path.equals("/www") || path.equals("/www/") || path.startsWith("/www/")) {
            String rel = path.substring(1);
            if ("www".equals(rel) || "www/".equals(rel)) rel = "www/index.html";
            if (rel.contains("..") || rel.contains("\\")) return makeStatusResponse(403, "Forbidden");
            try {
                InputStream in = getAssets().open(rel);
                String mime = rel.endsWith(".html") ? "text/html"
                        : rel.endsWith(".css") ? "text/css"
                        : rel.endsWith(".js") ? "application/javascript"
                        : rel.endsWith(".png") ? "image/png" : "application/octet-stream";
                Map<String, String> headers = new HashMap<>();
                headers.put("Cache-Control", "no-store");
                return new WebResourceResponse(mime, "utf-8", 200, "OK", headers, in);
            } catch (IOException e) {
                return makeStatusResponse(404, "Not Found");
            }
        }
        if (path.equals("/api/preview")) {
            String id = uri.getQueryParameter("id");
            String kind = uri.getQueryParameter("kind");
            if (id == null || kind == null || id.contains("..") || kind.contains("..")
                    || id.contains("/") || kind.contains("/")) {
                return makeStatusResponse(400, "Bad Request");
            }
            boolean isPng = "orig_zoom".equals(kind) || "clean_zoom".equals(kind);
            String ext = isPng ? ".png" : ".jpg";
            File prevFile = new File(new File(getCacheDir(), "previews"), id + "_" + kind + ext);
            if (!prevFile.isFile()) {
                return makeStatusResponse(404, "Preview Not Ready");
            }
            try {
                FileInputStream fis = new FileInputStream(prevFile);
                Map<String, String> headers = new HashMap<>();
                headers.put("Cache-Control", "no-store");
                return new WebResourceResponse(isPng ? "image/png" : "image/jpeg", null, 200, "OK", headers, fis);
            } catch (IOException e) {
                return makeStatusResponse(500, "IO Error");
            }
        }
        return makeStatusResponse(404, "Not Found");
    }

    private static WebResourceResponse makeStatusResponse(int code, String msg) {
        return new WebResourceResponse(
                "text/plain",
                "utf-8",
                code,
                msg,
                Collections.singletonMap("Cache-Control", "no-store"),
                new ByteArrayInputStream(new byte[0]));
    }

    // -------------------------------------------------------------------------
    // Incoming Image Pick & Share Intents
    // -------------------------------------------------------------------------

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = getParcelableUri(intent, Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            List<Uri> list = getParcelableUriList(intent, Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            Uri u = intent.getData();
            if (u != null) uris.add(u);
        }
        if (uris.isEmpty() && intent.getClipData() != null) {
            ClipData cd = intent.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u != null && !uris.contains(u)) uris.add(u);
            }
        }
        if (!uris.isEmpty()) {
            enqueueUris(uris);
        }
    }

    @SuppressWarnings({"deprecation", "unchecked"})
    private static Uri getParcelableUri(Intent intent, String key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(key, Uri.class);
        }
        return intent.getParcelableExtra(key);
    }

    @SuppressWarnings({"deprecation", "unchecked"})
    private static List<Uri> getParcelableUriList(Intent intent, String key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableArrayListExtra(key, Uri.class);
        }
        return intent.getParcelableArrayListExtra(key);
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择需要去水印的图片（可多选）"), PICK_IMAGES_CODE);
        } catch (Exception e) {
            mainHandler.post(() -> Toast.makeText(this, "无法打开系统图库选择器", Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_IMAGES_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                List<Uri> uris = new ArrayList<>();
                ClipData cd = data.getClipData();
                if (cd != null && cd.getItemCount() > 0) {
                    for (int i = 0; i < cd.getItemCount(); i++) {
                        Uri u = cd.getItemAt(i).getUri();
                        if (u != null) uris.add(u);
                    }
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
                if (!uris.isEmpty()) {
                    enqueueUris(uris);
                }
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // -------------------------------------------------------------------------
    // Processing Queue & Image I/O
    // -------------------------------------------------------------------------

    private void enqueueUris(List<Uri> uris) {
        File incomingDir = new File(getCacheDir(), "incoming");
        incomingDir.mkdirs();

        for (Uri uri : uris) {
            String displayName = queryDisplayName(uri);
            String baseName = stripQueryAndSlash(displayName);
            String stem = stripExtension(baseName);
            if (stem.endsWith("_去水印")) {
                stem = stem.substring(0, stem.length() - "_去水印".length());
            }
            if (stem.isEmpty()) stem = "image";

            String id = UUID.randomUUID().toString().substring(0, 10);
            File stagedFile = new File(incomingDir, id + ".img");
            try {
                copyUriToFile(uri, stagedFile);
            } catch (IOException e) {
                continue;
            }

            QueueItem item = new QueueItem(id, baseName, stem);
            synchronized (stateLock) {
                items.put(id, item);
                order.add(id);
                if (selectedId == null || !items.containsKey(selectedId)) {
                    selectedId = id;
                }
            }
            pushStateToWeb();

            final File inputCopy = stagedFile;
            final QueueItem qItem = item;
            workerPool.execute(() -> processOneItem(qItem, inputCopy));
        }
    }

    private void processOneItem(QueueItem item, File inputFile) {
        long t0 = System.nanoTime();
        item.status = "processing";
        item.detail = "正在分析并去除水印...";
        pushStateToWeb();

        try {
            ensureTemplateLoaded();

            // 1. Read EXIF orientation safely before decoding pixels
            int exifOrientation = ExifInterface.ORIENTATION_NORMAL;
            try (InputStream ein = new FileInputStream(inputFile)) {
                ExifInterface exif = new ExifInterface(ein);
                exifOrientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } catch (Exception ignored) {
            }

            // 2. Bounds check to avoid OOM on gigantic canvases
            BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
            boundsOpts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(inputFile.getAbsolutePath(), boundsOpts);
            int rawW = boundsOpts.outWidth;
            int rawH = boundsOpts.outHeight;
            if (rawW <= 0 || rawH <= 0) {
                item.status = "error";
                item.detail = "无法解码该图片格式";
                pushStateToWeb();
                return;
            }
            if ((long) rawW * rawH > 48_000_000L) {
                item.status = "error";
                item.detail = String.format("分辨率过大 (%dx%d)，超出移动端安全内存限制", rawW, rawH);
                pushStateToWeb();
                return;
            }

            // 3. Decode un-premultiplied ARGB_8888 pixels in sRGB
            BitmapFactory.Options decOpts = new BitmapFactory.Options();
            decOpts.inJustDecodeBounds = false;
            decOpts.inScaled = false;
            decOpts.inPremultiplied = false;
            decOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                decOpts.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
            }

            Bitmap decoded = BitmapFactory.decodeFile(inputFile.getAbsolutePath(), decOpts);
            if (decoded == null) {
                item.status = "error";
                item.detail = "图片解码失败";
                pushStateToWeb();
                return;
            }

            int w = decoded.getWidth();
            int h = decoded.getHeight();
            int[] argb = new int[w * h];
            decoded.getPixels(argb, 0, w, 0, 0, w, h);
            decoded.recycle();

            // 4. Normalize EXIF rotation directly on int[] without touching un-premultiplied Canvas
            if (exifOrientation != ExifInterface.ORIENTATION_NORMAL
                    && exifOrientation != ExifInterface.ORIENTATION_UNDEFINED) {
                int[] rotDims = new int[2];
                argb = applyExifOrientationArgb(argb, w, h, exifOrientation, rotDims);
                w = rotDims[0];
                h = rotDims[1];
            }

            item.width = w;
            item.height = h;

            File previewDir = new File(getCacheDir(), "previews");
            previewDir.mkdirs();

            // Capture original full preview before mutating argb in-place
            saveDownsampledJpeg(argb, w, h, 1500, new File(previewDir, item.id + "_orig_full.jpg"));
            item.hasFull = true;

            // 5. Run dual-engine watermark remover in-place
            RunningHubEngine.ProcessResult res =
                    RunningHubEngine.processInPlace(argb, w, h, tmplData, tmplW, tmplH);

            if (!"done".equals(res.status)) {
                item.status = "skipped";
                item.mode = "";
                item.modeLabel = "未修改";
                item.detail = res.detail;
                item.elapsedSec = (System.nanoTime() - t0) / 1e9;
                pushStateToWeb();
                return;
            }

            // 6. Generate zoom comparison PNGs (up-scaled for crisp viewing) and clean full preview JPEG
            if (res.origPatchArgb != null && res.cleanPatchArgb != null && res.patchW > 0 && res.patchH > 0) {
                saveZoomPatchPng(res.origPatchArgb, res.patchW, res.patchH,
                        new File(previewDir, item.id + "_orig_zoom.png"));
                saveZoomPatchPng(res.cleanPatchArgb, res.patchW, res.patchH,
                        new File(previewDir, item.id + "_clean_zoom.png"));
                item.hasZoom = true;
            }
            saveDownsampledJpeg(argb, w, h, 1500, new File(previewDir, item.id + "_clean_full.jpg"));

            // 7. Save full-resolution lossless PNG to cache/outputs/<id>/<stem>_去水印.png
            File outDir = new File(new File(getCacheDir(), "outputs"), item.id);
            outDir.mkdirs();
            String outName = item.stem + "_去水印.png";
            File outFile = new File(outDir, outName);

            premultiplyIfNeeded(argb);
            Bitmap outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            outBmp.setPixels(argb, 0, w, 0, 0, w, h);
            argb = null; // Release large pixel array promptly for GC

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                outBmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            } finally {
                outBmp.recycle();
            }

            item.outputName = outName;
            item.outputPath = outFile.getAbsolutePath();
            item.outSizeMb = outFile.length() / (1024.0 * 1024.0);
            item.status = "done";
            item.mode = res.mode;
            item.modeLabel = res.modeLabel;
            item.detail = res.detail;
            item.opacity = res.opacity;
            item.score = res.score;
            item.corner = res.corner;
            item.elapsedSec = (System.nanoTime() - t0) / 1e9;
        } catch (OutOfMemoryError oom) {
            item.status = "error";
            item.detail = "手机可用内存不足，建议单张处理";
        } catch (Exception e) {
            item.status = "error";
            item.detail = "处理出错: " + e.getMessage();
        } finally {
            if (inputFile.exists()) {
                inputFile.delete();
            }
        }
        pushStateToWeb();
    }

    private synchronized void ensureTemplateLoaded() throws IOException {
        if (tmplData != null) return;
        AssetManager am = getAssets();
        try (InputStream is = am.open("rh_template.png")) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScaled = false;
            opts.inPremultiplied = false;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
            if (bmp == null) throw new IOException("Cannot decode rh_template.png");
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int[] raw = new int[w * h];
            bmp.getPixels(raw, 0, w, 0, 0, w, h);
            bmp.recycle();

            float[] t = new float[w * h];
            for (int i = 0; i < raw.length; i++) {
                int c = raw[i];
                int r = (c >>> 16) & 0xFF;
                int g = (c >>> 8) & 0xFF;
                int b = c & 0xFF;
                t[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f;
            }
            tmplW = w;
            tmplH = h;
            tmplData = t;
        }
    }

    // -------------------------------------------------------------------------
    // Preview Encoding & Image Helpers
    // -------------------------------------------------------------------------

    private static void premultiplyIfNeeded(int[] argb) {
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            int a = (c >>> 24) & 0xFF;
            if (a == 255) continue;
            if (a == 0) {
                argb[i] = 0;
                continue;
            }
            int r = (((c >>> 16) & 0xFF) * a + 127) / 255;
            int g = (((c >>> 8) & 0xFF) * a + 127) / 255;
            int b = ((c & 0xFF) * a + 127) / 255;
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    private static void saveDownsampledJpeg(int[] argb, int w, int h, int maxSide, File out) throws IOException {
        int dstW = w;
        int dstH = h;
        if (Math.max(w, h) > maxSide) {
            double sc = maxSide / (double) Math.max(w, h);
            dstW = Math.max(1, (int) Math.round(w * sc));
            dstH = Math.max(1, (int) Math.round(h * sc));
        }
        int[] scaled = resampleBilinearOpaque(argb, w, h, dstW, dstH);
        Bitmap bmp = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888);
        bmp.setPixels(scaled, 0, dstW, 0, 0, dstW, dstH);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos);
        } finally {
            bmp.recycle();
        }
    }

    private static void saveZoomPatchPng(int[] patchArgb, int w, int h, File out) throws IOException {
        int dstW = w;
        int dstH = h;
        if (w < 640) {
            dstW = w * 2;
            dstH = h * 2;
        } else if (w > 1300) {
            double sc = 1300.0 / w;
            dstW = 1300;
            dstH = Math.max(1, (int) Math.round(h * sc));
        }
        int[] scaled = (dstW == w && dstH == h)
                ? makeOpaqueCopy(patchArgb)
                : resampleBilinearOpaque(patchArgb, w, h, dstW, dstH);
        Bitmap bmp = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888);
        bmp.setPixels(scaled, 0, dstW, 0, 0, dstW, dstH);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } finally {
            bmp.recycle();
        }
    }

    private static int[] makeOpaqueCopy(int[] src) {
        int[] out = new int[src.length];
        for (int i = 0; i < src.length; i++) {
            out[i] = src[i] | 0xFF000000;
        }
        return out;
    }

    private static int[] resampleBilinearOpaque(int[] src, int sw, int sh, int dw, int dh) {
        int[] dst = new int[dw * dh];
        double scaleX = sw / (double) dw;
        double scaleY = sh / (double) dh;
        for (int y = 0; y < dh; y++) {
            double sy = (y + 0.5) * scaleY - 0.5;
            int y0 = Math.max(0, Math.min(sh - 1, (int) Math.floor(sy)));
            int y1 = Math.min(sh - 1, y0 + 1);
            double fy = Math.max(0.0, sy - y0);
            int row0 = y0 * sw;
            int row1 = y1 * sw;
            int dstRow = y * dw;
            for (int x = 0; x < dw; x++) {
                double sx = (x + 0.5) * scaleX - 0.5;
                int x0 = Math.max(0, Math.min(sw - 1, (int) Math.floor(sx)));
                int x1 = Math.min(sw - 1, x0 + 1);
                double fx = Math.max(0.0, sx - x0);
                int c00 = src[row0 + x0], c10 = src[row0 + x1];
                int c01 = src[row1 + x0], c11 = src[row1 + x1];
                double w00 = (1.0 - fx) * (1.0 - fy);
                double w10 = fx * (1.0 - fy);
                double w01 = (1.0 - fx) * fy;
                double w11 = fx * fy;
                int rr = (int) Math.round(((c00 >>> 16) & 255) * w00 + ((c10 >>> 16) & 255) * w10
                        + ((c01 >>> 16) & 255) * w01 + ((c11 >>> 16) & 255) * w11);
                int gg = (int) Math.round(((c00 >>> 8) & 255) * w00 + ((c10 >>> 8) & 255) * w10
                        + ((c01 >>> 8) & 255) * w01 + ((c11 >>> 8) & 255) * w11);
                int bb = (int) Math.round((c00 & 255) * w00 + (c10 & 255) * w10
                        + (c01 & 255) * w01 + (c11 & 255) * w11);
                dst[dstRow + x] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
            }
        }
        return dst;
    }

    private static int[] applyExifOrientationArgb(int[] src, int w, int h, int orient, int[] outDims) {
        boolean swap = (orient == ExifInterface.ORIENTATION_ROTATE_90
                || orient == ExifInterface.ORIENTATION_ROTATE_270
                || orient == ExifInterface.ORIENTATION_TRANSPOSE
                || orient == ExifInterface.ORIENTATION_TRANSVERSE);
        int dw = swap ? h : w;
        int dh = swap ? w : h;
        outDims[0] = dw;
        outDims[1] = dh;
        int[] dst = new int[dw * dh];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx, dy;
                switch (orient) {
                    case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                        dx = w - 1 - x; dy = y; break;
                    case ExifInterface.ORIENTATION_ROTATE_180:
                        dx = w - 1 - x; dy = h - 1 - y; break;
                    case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                        dx = x; dy = h - 1 - y; break;
                    case ExifInterface.ORIENTATION_TRANSPOSE:
                        dx = y; dy = x; break;
                    case ExifInterface.ORIENTATION_ROTATE_90:
                        dx = h - 1 - y; dy = x; break;
                    case ExifInterface.ORIENTATION_TRANSVERSE:
                        dx = h - 1 - y; dy = w - 1 - x; break;
                    case ExifInterface.ORIENTATION_ROTATE_270:
                        dx = y; dy = w - 1 - x; break;
                    default:
                        dx = x; dy = y; break;
                }
                dst[dy * dw + dx] = src[y * w + x];
            }
        }
        return dst;
    }

    // -------------------------------------------------------------------------
    // Save to Gallery & Share Export
    // -------------------------------------------------------------------------

    private void saveToGalleryInternal(String targetId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingSaveActionId = targetId;
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, WRITE_PERM_CODE);
                return;
            }
        }
        new Thread(() -> {
            List<QueueItem> targets = new ArrayList<>();
            synchronized (stateLock) {
                if ("__ALL__".equals(targetId)) {
                    for (String id : order) {
                        QueueItem it = items.get(id);
                        if (it != null && "done".equals(it.status) && !it.savedToGallery) {
                            targets.add(it);
                        }
                    }
                } else {
                    QueueItem it = items.get(targetId);
                    if (it != null && "done".equals(it.status)) {
                        targets.add(it);
                    }
                }
            }
            if (targets.isEmpty()) {
                showToast("没有可保存的图片");
                return;
            }
            int ok = 0;
            for (QueueItem it : targets) {
                if (writePngToGallery(it)) {
                    it.savedToGallery = true;
                    ok++;
                }
            }
            pushStateToWeb();
            if (ok > 0) {
                showToast("已保存 " + ok + " 张无损原图至相册/RunningHubClear");
            } else {
                showToast("保存到相册失败，请检查存储空间或权限");
            }
        }, "SaveGalleryThread").start();
    }

    private boolean writePngToGallery(QueueItem item) {
        File src = new File(item.outputPath);
        if (!src.isFile()) return false;
        String displayName = item.outputName;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + File.separator + "RunningHubClear");
            cv.put(MediaStore.Images.Media.IS_PENDING, 1);

            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return false;
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) return false;
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                cv.clear();
                cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, cv, null, null);
                return true;
            } catch (IOException e) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Exception ignored) {
                }
                return false;
            }
        } else {
            @SuppressWarnings("deprecation")
            File picDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "RunningHubClear");
            picDir.mkdirs();
            File dst = new File(picDir, displayName);
            if (dst.exists()) {
                dst = new File(picDir, item.stem + "_" + System.currentTimeMillis() + "_去水印.png");
            }
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } catch (IOException e) {
                return false;
            }
            MediaScannerConnection.scanFile(this, new String[]{dst.getAbsolutePath()},
                    new String[]{"image/png"}, null);
            return true;
        }
    }

    private void shareItemInternal(String id) {
        QueueItem it;
        synchronized (stateLock) {
            it = items.get(id);
        }
        if (it == null || !"done".equals(it.status) || it.outputPath.isEmpty()) {
            showToast("请等待处理完成后再分享");
            return;
        }
        String authority = getPackageName() + ".shareprovider";
        Uri shareUri = ShareFileProvider.uriForOutput(authority, it.id, it.outputName);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_STREAM, shareUri);
        intent.setClipData(ClipData.newUri(getContentResolver(), it.outputName, shareUri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, "分享去水印无损原图"));
        } catch (Exception e) {
            showToast("无法调起系统分享菜单");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == WRITE_PERM_CODE) {
            String pending = pendingSaveActionId;
            pendingSaveActionId = null;
            if (grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED && pending != null) {
                saveToGalleryInternal(pending);
            } else {
                showToast("未授予存储权限，无法写入系统相册");
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // -------------------------------------------------------------------------
    // State Serialization & Bridge
    // -------------------------------------------------------------------------

    private String buildStateJson() {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            int doneCnt = 0;
            int unsavedCnt = 0;
            synchronized (stateLock) {
                for (String id : order) {
                    QueueItem it = items.get(id);
                    if (it == null) continue;
                    if ("done".equals(it.status)) {
                        doneCnt++;
                        if (!it.savedToGallery) unsavedCnt++;
                    }
                    JSONObject obj = new JSONObject();
                    obj.put("id", it.id);
                    obj.put("name", it.name);
                    obj.put("output_name", it.outputName);
                    obj.put("status", it.status);
                    obj.put("mode", it.mode);
                    obj.put("mode_label", it.modeLabel);
                    obj.put("detail", it.detail);
                    obj.put("width", it.width);
                    obj.put("height", it.height);
                    obj.put("opacity", it.opacity);
                    obj.put("score", it.score);
                    obj.put("corner", it.corner);
                    obj.put("elapsed", Math.round(it.elapsedSec * 100.0) / 100.0);
                    obj.put("out_size_mb", Math.round(it.outSizeMb * 100.0) / 100.0);
                    obj.put("has_zoom", it.hasZoom);
                    obj.put("has_full", it.hasFull);
                    obj.put("saved", it.savedToGallery);
                    arr.put(obj);
                }
                root.put("selected_id", selectedId == null ? JSONObject.NULL : selectedId);
            }
            root.put("items", arr);
            root.put("total", arr.length());
            root.put("done_count", doneCnt);
            root.put("unsaved_count", unsavedCnt);
            root.put("version", APP_VERSION);
            return root.toString();
        } catch (Exception e) {
            return "{\"items\":[],\"selected_id\":null,\"total\":0,\"done_count\":0,\"unsaved_count\":0}";
        }
    }

    private void pushStateToWeb() {
        if (!pageLoaded || webView == null) return;
        final String stateJson = buildStateJson();
        final String js = "window.__onState && window.__onState(" + stateJson + ");";
        mainHandler.post(() -> {
            if (webView != null) {
                webView.evaluateJavascript(js, null);
            }
        });
    }

    public static final class AndroidBridge {
        private final MainActivity activity;

        AndroidBridge(MainActivity activity) {
            this.activity = activity;
        }

        @JavascriptInterface
        public void pickImages() {
            activity.mainHandler.post(activity::openImagePicker);
        }

        @JavascriptInterface
        public String getStateJson() {
            return activity.buildStateJson();
        }

        @JavascriptInterface
        public void selectItem(String id) {
            synchronized (activity.stateLock) {
                if (activity.items.containsKey(id)) {
                    activity.selectedId = id;
                }
            }
            activity.pushStateToWeb();
        }

        @JavascriptInterface
        public void removeItem(String id) {
            synchronized (activity.stateLock) {
                activity.items.remove(id);
                activity.order.remove(id);
                if (id.equals(activity.selectedId)) {
                    activity.selectedId = activity.order.isEmpty() ? null : activity.order.get(0);
                }
            }
            activity.deleteItemCache(id);
            activity.pushStateToWeb();
        }

        @JavascriptInterface
        public void clearQueue() {
            List<String> ids;
            synchronized (activity.stateLock) {
                ids = new ArrayList<>(activity.order);
                activity.items.clear();
                activity.order.clear();
                activity.selectedId = null;
            }
            for (String id : ids) activity.deleteItemCache(id);
            activity.pushStateToWeb();
        }

        @JavascriptInterface
        public void saveItemToGallery(String id) {
            activity.saveToGalleryInternal(id);
        }

        @JavascriptInterface
        public void saveAllToGallery() {
            activity.saveToGalleryInternal("__ALL__");
        }

        @JavascriptInterface
        public void shareItem(String id) {
            activity.mainHandler.post(() -> activity.shareItemInternal(id));
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private void showToast(String msg) {
        mainHandler.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    private String queryDisplayName(Uri uri) {
        String name = null;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.trim().isEmpty()) {
            String last = uri.getLastPathSegment();
            name = (last != null && !last.isEmpty()) ? last : "image.png";
        }
        return name;
    }

    private void copyUriToFile(Uri src, File dst) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) throw new IOException("Cannot open input stream");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static String stripQueryAndSlash(String name) {
        String s = name.replace("\\", "/");
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        return s.isEmpty() ? "image.png" : s;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    private void deleteItemCache(String id) {
        File prevDir = new File(getCacheDir(), "previews");
        for (String sfx : new String[]{"_orig_full.jpg", "_clean_full.jpg", "_orig_zoom.png", "_clean_zoom.png"}) {
            File f = new File(prevDir, id + sfx);
            if (f.exists()) f.delete();
        }
        File outDir = new File(new File(getCacheDir(), "outputs"), id);
        deleteRecursive(outDir);
    }

    private void cleanupOldCache() {
        for (String sub : new String[]{"incoming", "previews", "outputs"}) {
            deleteRecursive(new File(getCacheDir(), sub));
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }
}
