package com.runninghub.clearstudio;

import java.util.Arrays;

/**
 * Pure-Java RunningHub AI Watermark Removal Engine (zero external dependencies).
 * - Mode A: Exact pixel-level inverse alpha unblending for RGBA PNGs with RunningHub alpha signature.
 * - Mode B: Multi-scale FFT normalized gradient template matching + sub-pixel refinement + Telea FMM inpainting.
 */
public final class RunningHubEngine {

    private RunningHubEngine() {
    }

    public static final class ProcessResult {
        public final String status; // "done" or "skipped"
        public final String mode;   // "Mode-A" or "Mode-B" or ""
        public final String modeLabel;
        public final String detail;
        public final int[] bbox;    // [x0, y0, x1, y1] in image coordinates
        public final double opacity;
        public final double score;
        public final String corner;
        public final int color;
        public final int patchX0;
        public final int patchY0;
        public final int patchW;
        public final int patchH;
        public final int[] origPatchArgb;
        public final int[] cleanPatchArgb;

        public ProcessResult(
                String status,
                String mode,
                String modeLabel,
                String detail,
                int[] bbox,
                double opacity,
                double score,
                String corner,
                int color,
                int patchX0,
                int patchY0,
                int patchW,
                int patchH,
                int[] origPatchArgb,
                int[] cleanPatchArgb
        ) {
            this.status = status;
            this.mode = mode;
            this.modeLabel = modeLabel;
            this.detail = detail;
            this.bbox = bbox;
            this.opacity = opacity;
            this.score = score;
            this.corner = corner;
            this.color = color;
            this.patchX0 = patchX0;
            this.patchY0 = patchY0;
            this.patchW = patchW;
            this.patchH = patchH;
            this.origPatchArgb = origPatchArgb;
            this.cleanPatchArgb = cleanPatchArgb;
        }

        static ProcessResult skipped(String reason) {
            return new ProcessResult("skipped", "", "", reason, null, 0.0, 0.0, "", 255, 0, 0, 0, 0, null, null);
        }
    }

    public static ProcessResult processInPlace(
            int[] argb,
            int width,
            int height,
            float[] tmpl,
            int tw0,
            int th0
    ) {
        if (argb == null || width <= 0 || height <= 0 || argb.length < width * height) {
            return ProcessResult.skipped("无效图像数据");
        }

        // Scan alpha channel statistics in a single pass
        int aMin = 255;
        int countLt255 = 0;
        int bx0 = width;
        int bx1 = 0;
        int by0 = height;
        int by1 = 0;

        for (int y = 0; y < height; y++) {
            int rowOff = y * width;
            for (int x = 0; x < width; x++) {
                int a = (argb[rowOff + x] >>> 24) & 0xFF;
                if (a < aMin) {
                    aMin = a;
                }
                if (a < 255) {
                    countLt255++;
                    if (x < bx0) bx0 = x;
                    if (x + 1 > bx1) bx1 = x + 1;
                    if (y < by0) by0 = y;
                    if (y + 1 > by1) by1 = y + 1;
                }
            }
        }

        boolean preserveRealAlpha = (aMin < 175);
        double frac = countLt255 / (double) (width * (long) height);

        if (aMin < 255 && aMin >= 175 && frac <= 0.15 && countLt255 >= 100) {
            ProcessResult alphaRes = removeViaAlpha(argb, width, height, bx0, by0, bx1, by1);
            if (alphaRes != null) {
                return alphaRes;
            }
        }

        if (tmpl == null || tw0 <= 0 || th0 <= 0) {
            return ProcessResult.skipped("未检测到透明水印特征，且未加载模板文件");
        }
        return removeViaTemplate(argb, width, height, tmpl, tw0, th0, preserveRealAlpha);
    }

    // -------------------------------------------------------------------------
    // Mode-A: Exact Inverse Alpha Unblending
    // -------------------------------------------------------------------------

    private static ProcessResult removeViaAlpha(
            int[] argb,
            int W,
            int H,
            int bx0,
            int by0,
            int bx1,
            int by1
    ) {
        int pad = 24;
        int x0 = Math.max(0, bx0 - pad);
        int x1 = Math.min(W, bx1 + pad);
        int y0 = Math.max(0, by0 - pad);
        int y1 = Math.min(H, by1 + pad);
        int pw = x1 - x0;
        int ph = y1 - y0;
        int n = pw * ph;

        float[] r = new float[n];
        float[] g = new float[n];
        float[] b = new float[n];
        float[] aCh = new float[n];
        int[] origPatch = new int[n];
        int[] histLow = new int[235];

        for (int py = 0; py < ph; py++) {
            int srcOff = (y0 + py) * W + x0;
            int dstOff = py * pw;
            for (int px = 0; px < pw; px++) {
                int c = argb[srcOff + px];
                origPatch[dstOff + px] = c;
                int a = (c >>> 24) & 0xFF;
                r[dstOff + px] = (c >>> 16) & 0xFF;
                g[dstOff + px] = (c >>> 8) & 0xFF;
                b[dstOff + px] = c & 0xFF;
                aCh[dstOff + px] = a;
                if (a < 235) {
                    histLow[a]++;
                }
            }
        }

        int aMode = 194;
        int bestHist = 0;
        for (int a = 0; a < 235; a++) {
            if (histLow[a] > bestHist) {
                bestHist = histLow[a];
                aMode = a;
            }
        }

        boolean[] strictInterior = new boolean[n];
        for (int y = 0; y < ph; y++) {
            int ym = Math.max(0, y - 1);
            int yp = Math.min(ph - 1, y + 1);
            int row = y * pw;
            for (int x = 0; x < pw; x++) {
                int xm = Math.max(0, x - 1);
                int xp = Math.min(pw - 1, x + 1);
                float av = aCh[row + x];
                if (av <= aMode + 1
                        && aCh[ym * pw + x] <= aMode + 2
                        && aCh[yp * pw + x] <= aMode + 2
                        && aCh[row + xm] <= aMode + 2
                        && aCh[row + xp] <= aMode + 2) {
                    strictInterior[row + x] = true;
                }
            }
        }

        double[] cfg = estimateAlphaCoreParams(r, g, b, aCh, strictInterior, pw, ph, aMode);
        float CVal = (float) cfg[0];
        float tCoreVal = (float) cfg[1];
        boolean isUpper = cfg[2] > 0.5;

        float[] tLowAdj = new float[n];
        float[] tHighAdj = new float[n];
        float[] tInterior = new float[n];
        for (int i = 0; i < n; i++) {
            double disc = Math.max(0.0, 4.0 * (aCh[i] / 255.0) - 3.0);
            double sq = Math.sqrt(disc);
            float tl = clamp((float) (0.5 * (1.0 - sq)), 0.0f, 0.85f);
            float th = clamp((float) (0.5 * (1.0 + sq)), 0.0f, 0.85f);
            tLowAdj[i] = tl;
            tHighAdj[i] = th;
            if ((int) Math.round(aCh[i]) == aMode) {
                if (isUpper) {
                    tHighAdj[i] = tCoreVal;
                } else {
                    tLowAdj[i] = tCoreVal;
                }
            }
            tInterior[i] = isUpper ? tHighAdj[i] : tLowAdj[i];
        }

        // Pass 1: Recover strict interior & build clean boundary reference via Telea
        int[] workRgb = new int[n * 3];
        boolean[] bandMask = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (strictInterior[i]) {
                float ti = tInterior[i];
                float denom = Math.max(1e-4f, 1.0f - ti);
                workRgb[i * 3] = clampInt(Math.round((r[i] - ti * CVal) / denom), 0, 255);
                workRgb[i * 3 + 1] = clampInt(Math.round((g[i] - ti * CVal) / denom), 0, 255);
                workRgb[i * 3 + 2] = clampInt(Math.round((b[i] - ti * CVal) / denom), 0, 255);
            } else {
                workRgb[i * 3] = clampInt(Math.round(r[i]), 0, 255);
                workRgb[i * 3 + 1] = clampInt(Math.round(g[i]), 0, 255);
                workRgb[i * 3 + 2] = clampInt(Math.round(b[i]), 0, 255);
                if (aCh[i] < 254.0f) {
                    bandMask[i] = true;
                }
            }
        }

        int[] refBgInt = teleaInpaint(workRgb, pw, ph, bandMask, 3);
        float[] distToStrict = distanceTransformL2(strictInterior, pw, ph);

        int maxCandA = Math.max(aMode, 194);
        boolean[] cand = new boolean[n];
        boolean anyCand = false;
        float[] tMap = new float[n];
        boolean[] pickHigh2 = new boolean[n];

        for (int i = 0; i < n; i++) {
            if (!strictInterior[i] && aCh[i] <= maxCandA) {
                cand[i] = true;
                anyCand = true;
            }
            if (isUpper) {
                if (strictInterior[i]) {
                    tMap[i] = tInterior[i];
                } else if (cand[i] && distToStrict[i] <= 1.5f) {
                    float errH = unblendError(r[i], g[i], b[i], tHighAdj[i], CVal,
                            refBgInt[i * 3], refBgInt[i * 3 + 1], refBgInt[i * 3 + 2]);
                    float errL = unblendError(r[i], g[i], b[i], tLowAdj[i], CVal,
                            refBgInt[i * 3], refBgInt[i * 3 + 1], refBgInt[i * 3 + 2]);
                    tMap[i] = (errH < errL) ? tHighAdj[i] : tLowAdj[i];
                } else {
                    tMap[i] = tLowAdj[i];
                }
            } else {
                tMap[i] = tLowAdj[i];
            }
        }

        // Pass 2: 5x5 median consistency check on ambiguous cand pixels
        if (isUpper && anyCand) {
            int[] recP1 = new int[n * 3];
            for (int i = 0; i < n; i++) {
                float denom = Math.max(1e-4f, 1.0f - tMap[i]);
                recP1[i * 3] = clampInt(Math.round((r[i] - tMap[i] * CVal) / denom), 0, 255);
                recP1[i * 3 + 1] = clampInt(Math.round((g[i] - tMap[i] * CVal) / denom), 0, 255);
                recP1[i * 3 + 2] = clampInt(Math.round((b[i] - tMap[i] * CVal) / denom), 0, 255);
            }
            int[] med5 = median5x5OnMask(recP1, pw, ph, cand);
            for (int i = 0; i < n; i++) {
                if (strictInterior[i]) {
                    tMap[i] = tInterior[i];
                } else if (cand[i] && distToStrict[i] <= 1.5f) {
                    float errH = unblendError(r[i], g[i], b[i], tHighAdj[i], CVal,
                            med5[i * 3], med5[i * 3 + 1], med5[i * 3 + 2]);
                    float errL = unblendError(r[i], g[i], b[i], tLowAdj[i], CVal,
                            med5[i * 3], med5[i * 3 + 1], med5[i * 3 + 2]);
                    if (errH < errL) {
                        pickHigh2[i] = true;
                        tMap[i] = tHighAdj[i];
                    } else {
                        tMap[i] = tLowAdj[i];
                    }
                } else {
                    tMap[i] = tLowAdj[i];
                }
            }
        }

        // Pass 3: Sub-quantization refinement in flat-derivative trough A in [191, 193]
        float[] tRefined = Arrays.copyOf(tMap, n);
        for (int i = 0; i < n; i++) {
            if (!strictInterior[i] && aCh[i] <= 193.0f) {
                float av = aCh[i];
                double loA = Math.max(191.25, av - 0.52);
                double hiA = Math.min(255.0, av + 0.52);
                double dLo = Math.sqrt(Math.max(0.0, 4.0 * (loA / 255.0) - 3.0));
                double dHi = Math.sqrt(Math.max(0.0, 4.0 * (hiA / 255.0) - 3.0));
                double tMin;
                double tMax;
                if (Math.round(av) == 191) {
                    tMin = 0.5 * (1.0 - dHi);
                    tMax = 0.5 * (1.0 + dHi);
                } else if (pickHigh2[i]) {
                    tMin = 0.5 * (1.0 + dLo);
                    tMax = 0.5 * (1.0 + dHi);
                } else {
                    tMin = 0.5 * (1.0 - dHi);
                    tMax = 0.5 * (1.0 - dLo);
                }
                float bgR = refBgInt[i * 3];
                float bgG = refBgInt[i * 3 + 1];
                float bgB = refBgInt[i * 3 + 2];
                double sumRatio = 0.0;
                int cntValid = 0;
                if (CVal - bgR > 30.0f) {
                    sumRatio += (r[i] - bgR) / (CVal - bgR);
                    cntValid++;
                }
                if (CVal - bgG > 30.0f) {
                    sumRatio += (g[i] - bgG) / (CVal - bgG);
                    cntValid++;
                }
                if (CVal - bgB > 30.0f) {
                    sumRatio += (b[i] - bgB) / (CVal - bgB);
                    cntValid++;
                }
                if (cntValid > 0) {
                    double tRgb = sumRatio / cntValid;
                    tRefined[i] = (float) Math.max(tMin, Math.min(tMax, tRgb));
                }
            }
        }

        // Write clean RGB back in-place & capture clean patch
        int[] cleanPatch = new int[n];
        for (int py = 0; py < ph; py++) {
            int srcOff = py * pw;
            int dstOff = (y0 + py) * W + x0;
            for (int px = 0; px < pw; px++) {
                int idx = srcOff + px;
                float tr = tRefined[idx];
                float denom = Math.max(1e-4f, 1.0f - tr);
                int rr = clampInt(Math.round((r[idx] - tr * CVal) / denom), 0, 255);
                int gg = clampInt(Math.round((g[idx] - tr * CVal) / denom), 0, 255);
                int bb = clampInt(Math.round((b[idx] - tr * CVal) / denom), 0, 255);
                int outPx = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
                argb[dstOff + px] = outPx;
                cleanPatch[idx] = outPx;
            }
        }
        // Make entire image opaque RGB matching Python output behavior
        int total = W * H;
        for (int i = 0; i < total; i++) {
            argb[i] |= 0xFF000000;
        }

        String detail = String.format(
                "Alpha 精确逆混合 (区域=[%d:%d, %d:%d], t=%.2f, C=%.0f)",
                bx0, bx1, by0, by1, tCoreVal, CVal);
        return new ProcessResult(
                "done",
                "Mode-A",
                "Alpha 无损逆混合",
                detail,
                new int[]{bx0, by0, bx1, by1},
                Math.round(tCoreVal * 1000.0) / 1000.0,
                1.0,
                "TL",
                Math.round(CVal),
                x0, y0, pw, ph,
                origPatch,
                cleanPatch
        );
    }

    private static double[] estimateAlphaCoreParams(
            float[] r, float[] g, float[] b, float[] aCh,
            boolean[] strictInterior, int pw, int ph, int aMode
    ) {
        int n = pw * ph;
        double[] prefixCnt = new double[(pw + 1) * (ph + 1)];
        double[] prefixR = new double[(pw + 1) * (ph + 1)];
        double[] prefixG = new double[(pw + 1) * (ph + 1)];
        double[] prefixB = new double[(pw + 1) * (ph + 1)];
        int stride = pw + 1;

        for (int y = 0; y < ph; y++) {
            double rowCnt = 0, rowR = 0, rowG = 0, rowB = 0;
            int row = y * pw;
            int pRow = (y + 1) * stride;
            int prevRow = y * stride;
            for (int x = 0; x < pw; x++) {
                float cm = (aCh[row + x] >= 254.0f) ? 1.0f : 0.0f;
                rowCnt += cm;
                rowR += r[row + x] * cm;
                rowG += g[row + x] * cm;
                rowB += b[row + x] * cm;
                prefixCnt[pRow + x + 1] = prefixCnt[prevRow + x + 1] + rowCnt;
                prefixR[pRow + x + 1] = prefixR[prevRow + x + 1] + rowR;
                prefixG[pRow + x + 1] = prefixG[prevRow + x + 1] + rowG;
                prefixB[pRow + x + 1] = prefixB[prevRow + x + 1] + rowB;
            }
        }

        int rad = 8; // 17x17 window
        int validCnt = 0;
        int modeCnt = 0;
        for (int y = 0; y < ph; y++) {
            int y0 = Math.max(0, y - rad);
            int y1 = Math.min(ph, y + rad + 1);
            for (int x = 0; x < pw; x++) {
                int idx = y * pw + x;
                if (!strictInterior[idx]) continue;
                int x0 = Math.max(0, x - rad);
                int x1 = Math.min(pw, x + rad + 1);
                double cnt = boxSum(prefixCnt, stride, x0, y0, x1, y1);
                if (cnt > 25.0) {
                    validCnt++;
                    if (Math.round(aCh[idx]) == aMode) {
                        modeCnt++;
                    }
                }
            }
        }
        if (validCnt < 20) {
            return new double[]{255.0, 0.6000, 1.0};
        }
        boolean useMode = (modeCnt >= 15);
        int sampleCount = useMode ? modeCnt : validCnt;
        float[] bgR = new float[sampleCount];
        float[] bgG = new float[sampleCount];
        float[] bgB = new float[sampleCount];
        float[] wmR = new float[sampleCount];
        float[] wmG = new float[sampleCount];
        float[] wmB = new float[sampleCount];
        int k = 0;
        for (int y = 0; y < ph; y++) {
            int y0 = Math.max(0, y - rad);
            int y1 = Math.min(ph, y + rad + 1);
            for (int x = 0; x < pw; x++) {
                int idx = y * pw + x;
                if (!strictInterior[idx]) continue;
                if (useMode && Math.round(aCh[idx]) != aMode) continue;
                int x0 = Math.max(0, x - rad);
                int x1 = Math.min(pw, x + rad + 1);
                double cnt = boxSum(prefixCnt, stride, x0, y0, x1, y1);
                if (cnt > 25.0) {
                    bgR[k] = (float) (boxSum(prefixR, stride, x0, y0, x1, y1) / cnt);
                    bgG[k] = (float) (boxSum(prefixG, stride, x0, y0, x1, y1) / cnt);
                    bgB[k] = (float) (boxSum(prefixB, stride, x0, y0, x1, y1) / cnt);
                    wmR[k] = r[idx];
                    wmG[k] = g[idx];
                    wmB[k] = b[idx];
                    k++;
                }
            }
        }

        double disc = Math.max(0.0, 4.0 * (aMode / 255.0) - 3.0);
        double sq = Math.sqrt(disc);
        double[] candTs = (aMode == 194)
                ? new double[]{0.5 * (1.0 + sq), 0.5 * (1.0 - sq), 0.6000, 0.4000}
                : new double[]{0.5 * (1.0 + sq), 0.5 * (1.0 - sq)};
        double[] candCs = new double[]{255.0, 250.0, 245.0};

        double bestMae = 1e18;
        double[] bestCfg = new double[]{255.0, 0.6000, 1.0};
        for (double cCand : candCs) {
            for (double tCand : candTs) {
                if (tCand >= 0.92 || tCand <= 0.05) continue;
                double denom = 1.0 - tCand;
                double errSum = 0.0;
                for (int i = 0; i < sampleCount; i++) {
                    double rr = clamp((float) ((wmR[i] - tCand * cCand) / denom), 0.0f, 255.0f);
                    double gg = clamp((float) ((wmG[i] - tCand * cCand) / denom), 0.0f, 255.0f);
                    double bb = clamp((float) ((wmB[i] - tCand * cCand) / denom), 0.0f, 255.0f);
                    errSum += Math.abs(rr - bgR[i]) + Math.abs(gg - bgG[i]) + Math.abs(bb - bgB[i]);
                }
                double mae = errSum / (3.0 * sampleCount);
                if (mae < bestMae) {
                    bestMae = mae;
                    bestCfg = new double[]{cCand, tCand, tCand >= 0.5 ? 1.0 : 0.0};
                }
            }
        }
        return bestCfg;
    }

    private static double boxSum(double[] pref, int stride, int x0, int y0, int x1, int y1) {
        return pref[y1 * stride + x1] - pref[y0 * stride + x1]
                - pref[y1 * stride + x0] + pref[y0 * stride + x0];
    }

    private static float unblendError(
            float r, float g, float b, float t, float CVal,
            int refR, int refG, int refB
    ) {
        float denom = Math.max(1e-4f, 1.0f - t);
        float rr = clamp((r - t * CVal) / denom, 0.0f, 255.0f);
        float gg = clamp((g - t * CVal) / denom, 0.0f, 255.0f);
        float bb = clamp((b - t * CVal) / denom, 0.0f, 255.0f);
        return Math.abs(rr - refR) + Math.abs(gg - refG) + Math.abs(bb - refB);
    }

    // -------------------------------------------------------------------------
    // Mode-B: Multi-Scale Gradient Template Matching + Sub-Pixel Repair
    // -------------------------------------------------------------------------

    private static ProcessResult removeViaTemplate(
            int[] argb,
            int W,
            int H,
            float[] tmpl,
            int tw0,
            int th0,
            boolean preserveRealAlpha
    ) {
        int shortSide = Math.min(H, W);
        int roiH = Math.min(H, Math.max(120, (int) (0.28 * H)));
        int roiW = Math.min(W, Math.max(300, (int) (0.65 * W)));

        String[] cornerNames = {"TL", "TR", "BL", "BR"};
        int[][] cornerBounds = {
                {0, roiH, 0, roiW},
                {0, roiH, Math.max(0, W - roiW), W},
                {Math.max(0, H - roiH), H, 0, roiW},
                {Math.max(0, H - roiH), H, Math.max(0, W - roiW), W}
        };

        String bestCorner = null;
        double fineCorr = 0.0;
        int wF = 0, hF = 0, xF = 0, yF = 0;

        for (int ci = 0; ci < 4; ci++) {
            int cy0 = cornerBounds[ci][0], cy1 = cornerBounds[ci][1];
            int cx0 = cornerBounds[ci][2], cx1 = cornerBounds[ci][3];
            int cw = cx1 - cx0;
            int ch = cy1 - cy0;
            float[] roiGray = new float[cw * ch];
            for (int y = 0; y < ch; y++) {
                int srcOff = (cy0 + y) * W + cx0;
                int dstOff = y * cw;
                for (int x = 0; x < cw; x++) {
                    int c = argb[srcOff + x];
                    int rr = (c >>> 16) & 0xFF;
                    int gg = (c >>> 8) & 0xFF;
                    int bb = c & 0xFF;
                    // OpenCV COLOR_RGB2GRAY weights
                    roiGray[dstOff + x] = 0.299f * rr + 0.587f * gg + 0.114f * bb;
                }
            }

            double[] hit = searchRoiForTemplate(roiGray, cw, ch, tmpl, tw0, th0, shortSide, W);
            if (hit != null && hit[0] >= 0.30) {
                bestCorner = cornerNames[ci];
                fineCorr = hit[0];
                wF = (int) hit[1];
                hF = (int) hit[2];
                xF = cx0 + (int) hit[3];
                yF = cy0 + (int) hit[4];
                break;
            }
        }

        if (bestCorner == null) {
            return ProcessResult.skipped("未检测到 RunningHub AI 水印");
        }

        int pad = Math.max(16, (int) (0.12 * hF));
        int px0 = Math.max(0, xF - pad);
        int py0 = Math.max(0, yF - pad);
        int px1 = Math.min(W, xF + wF + pad);
        int py1 = Math.min(H, yF + hF + pad);
        int pw = px1 - px0;
        int ph = py1 - py0;
        int n = pw * ph;

        int[] origPatch = new int[n];
        float[] patchR = new float[n];
        float[] patchG = new float[n];
        float[] patchB = new float[n];
        int[] patchRgbInt = new int[n * 3];

        for (int py = 0; py < ph; py++) {
            int srcOff = (py0 + py) * W + px0;
            int dstOff = py * pw;
            for (int px = 0; px < pw; px++) {
                int c = argb[srcOff + px];
                int idx = dstOff + px;
                origPatch[idx] = c;
                int rr = (c >>> 16) & 0xFF;
                int gg = (c >>> 8) & 0xFF;
                int bb = c & 0xFF;
                patchR[idx] = rr;
                patchG[idx] = gg;
                patchB[idx] = bb;
                patchRgbInt[idx * 3] = rr;
                patchRgbInt[idx * 3 + 1] = gg;
                patchRgbInt[idx * 3 + 2] = bb;
            }
        }

        double ox = xF - px0;
        double oy = yF - py0;
        double sx = wF / (double) tw0;
        double sy = hF / (double) th0;

        float[] mInit = renderTemplatePatch(tmpl, tw0, th0, pw, ph, ox, oy, sx, sy, 0.0);
        int dilK = Math.max(5, ((int) Math.round(Math.max(5.0, hF * 0.04))) | 1);
        boolean[] wmInitBool = new boolean[n];
        for (int i = 0; i < n; i++) {
            wmInitBool[i] = mInit[i] > 0.02f;
        }
        boolean[] wmDil = dilateBox(wmInitBool, pw, ph, dilK);
        int[] refBgInt = teleaInpaint(patchRgbInt, pw, ph, wmDil, 4);

        // Verify white semi-transparent watermark contrast against local background
        double stepSum = 0.0;
        int fgCount = 0;
        for (int i = 0; i < n; i++) {
            if (mInit[i] >= 0.75f) {
                stepSum += (patchR[i] - refBgInt[i * 3])
                        + (patchG[i] - refBgInt[i * 3 + 1])
                        + (patchB[i] - refBgInt[i * 3 + 2]);
                fgCount++;
            }
        }
        if (fgCount < 15) {
            stepSum = 0.0;
            fgCount = 0;
            for (int i = 0; i < n; i++) {
                if (mInit[i] >= 0.45f) {
                    stepSum += (patchR[i] - refBgInt[i * 3])
                            + (patchG[i] - refBgInt[i * 3 + 1])
                            + (patchB[i] - refBgInt[i * 3 + 2]);
                    fgCount++;
                }
            }
        }
        if (fgCount < 10) {
            return ProcessResult.skipped("有效采样像素不足");
        }
        double stepMean = stepSum / (3.0 * fgCount);
        if (stepMean < 4.0) {
            return ProcessResult.skipped(String.format("亮度特征不符 RunningHub 白色半透明水印 (step=%.1f)", stepMean));
        }

        // Coordinate descent on (ox, oy, sx, sy, sigma) down to 0.1px
        float[] obsT = new float[n];
        for (int i = 0; i < n; i++) {
            float bgR = refBgInt[i * 3];
            float bgG = refBgInt[i * 3 + 1];
            float bgB = refBgInt[i * 3 + 2];
            float tR = (patchR[i] - bgR) / Math.max(35.0f, 255.0f - bgR);
            float tG = (patchG[i] - bgG) / Math.max(35.0f, 255.0f - bgG);
            float tB = (patchB[i] - bgB) / Math.max(35.0f, 255.0f - bgB);
            obsT[i] = (tR + tG + tB) / 3.0f;
        }
        boolean[] evalZone = dilateBox(wmInitBool, pw, ph, 7);
        int evalCount = 0;
        for (boolean z : evalZone) {
            if (z) evalCount++;
        }
        int[] evalIndices = new int[evalCount];
        int ei = 0;
        for (int i = 0; i < n; i++) {
            if (evalZone[i]) evalIndices[ei++] = i;
        }

        double sig = (wF < 600) ? 0.5 : 0.2;
        double[] evalCur = evalPatchLoss(tmpl, tw0, th0, pw, ph, obsT, evalIndices, ox, oy, sx, sy, sig);
        double curLoss = evalCur[0];
        double tCore = evalCur[1];

        double[] stepsPx = {1.5, 0.6, 0.25, 0.1};
        for (double stepPx : stepsPx) {
            for (int iter = 0; iter < 5; iter++) {
                boolean changed = false;
                for (int dir = -1; dir <= 1; dir += 2) {
                    double d = dir * stepPx;
                    double[] e = evalPatchLoss(tmpl, tw0, th0, pw, ph, obsT, evalIndices, ox + d, oy, sx, sy, sig);
                    if (e[0] < curLoss - 1e-7) {
                        ox += d; curLoss = e[0]; tCore = e[1]; changed = true;
                    }
                    e = evalPatchLoss(tmpl, tw0, th0, pw, ph, obsT, evalIndices, ox, oy + d, sx, sy, sig);
                    if (e[0] < curLoss - 1e-7) {
                        oy += d; curLoss = e[0]; tCore = e[1]; changed = true;
                    }
                    double dsx = (d * 1.5) / tw0;
                    e = evalPatchLoss(tmpl, tw0, th0, pw, ph, obsT, evalIndices, ox, oy, sx + dsx, sy, sig);
                    if (e[0] < curLoss - 1e-7) {
                        sx += dsx; curLoss = e[0]; tCore = e[1]; changed = true;
                    }
                    double dsy = (d * 1.5) / th0;
                    e = evalPatchLoss(tmpl, tw0, th0, pw, ph, obsT, evalIndices, ox, oy, sx, sy + dsy, sig);
                    if (e[0] < curLoss - 1e-7) {
                        sy += dsy; curLoss = e[0]; tCore = e[1]; changed = true;
                    }
                }
                for (int dir = -1; dir <= 1; dir += 2) {
                    double ns = Math.max(0.0, Math.min(1.5, sig + dir * 0.15));
                    if (Math.abs(ns - sig) > 1e-4) {
                        double[] e = evalPatchLoss(tmpl, tw0, th0, pw, ph, obsT, evalIndices, ox, oy, sx, sy, ns);
                        if (e[0] < curLoss - 1e-7) {
                            sig = ns; curLoss = e[0]; tCore = e[1]; changed = true;
                        }
                    }
                }
                if (!changed) break;
            }
        }

        float[] aligned = renderTemplatePatch(tmpl, tw0, th0, pw, ph, ox, oy, sx, sy, sig);
        if (Math.abs(tCore - 0.60) <= 0.035) {
            tCore = 0.60;
        }

        int[] cleanedPatchRgb;
        String methodTag;
        if (wF >= 650) {
            int[] rec = new int[n * 3];
            boolean[] coreGe94 = new boolean[n];
            boolean[] outerGt02 = new boolean[n];
            for (int i = 0; i < n; i++) {
                float al = aligned[i];
                float tEff = clamp((float) (tCore * al), 0.0f, 0.86f);
                float denom = 1.0f - tEff;
                rec[i * 3] = clampInt(Math.round((patchR[i] - tEff * 255.0f) / denom), 0, 255);
                rec[i * 3 + 1] = clampInt(Math.round((patchG[i] - tEff * 255.0f) / denom), 0, 255);
                rec[i * 3 + 2] = clampInt(Math.round((patchB[i] - tEff * 255.0f) / denom), 0, 255);
                coreGe94[i] = al >= 0.94f;
                outerGt02[i] = al > 0.02f;
            }
            boolean[] coreEroded = erodeBox(coreGe94, pw, ph, 5);
            boolean[] outerDil = dilateBox(outerGt02, pw, ph, 5);
            boolean[] ringMask = new boolean[n];
            for (int i = 0; i < n; i++) {
                ringMask[i] = outerDil[i] && !coreEroded[i];
            }
            cleanedPatchRgb = teleaInpaint(rec, pw, ph, ringMask, 3);
            methodTag = "核心逆混合+边界桥接";
        } else {
            int kS1 = Math.max(3, ((int) Math.round(Math.max(3.0, wF / 260.0))) | 1);
            boolean[] gt02 = new boolean[n];
            for (int i = 0; i < n; i++) {
                gt02[i] = aligned[i] > 0.02f;
            }
            boolean[] maskS1 = dilateBox(gt02, pw, ph, kS1);
            cleanedPatchRgb = teleaInpaint(patchRgbInt, pw, ph, maskS1, Math.max(3, kS1));
            methodTag = "细笔画自适应修补";
        }

        // Write cleaned patch back in-place (preserving alpha only if image genuinely has transparent artwork)
        int[] cleanPatchArgb = new int[n];
        for (int py = 0; py < ph; py++) {
            int srcOff = py * pw;
            int dstOff = (py0 + py) * W + px0;
            for (int px = 0; px < pw; px++) {
                int idx = srcOff + px;
                int rr = cleanedPatchRgb[idx * 3];
                int gg = cleanedPatchRgb[idx * 3 + 1];
                int bb = cleanedPatchRgb[idx * 3 + 2];
                int alpha = preserveRealAlpha ? (argb[dstOff + px] & 0xFF000000) : 0xFF000000;
                int outPx = alpha | (rr << 16) | (gg << 8) | bb;
                argb[dstOff + px] = outPx;
                cleanPatchArgb[idx] = outPx;
            }
        }
        if (!preserveRealAlpha) {
            int total = W * H;
            for (int i = 0; i < total; i++) {
                argb[i] |= 0xFF000000;
            }
        }

        String detail = String.format(
                "模板匹配-%s (%s, 相关度=%.2f, 区域=[%d:%d, %d:%d], t=%.2f)",
                methodTag, bestCorner, fineCorr, xF, xF + wF, yF, yF + hF, tCore);
        return new ProcessResult(
                "done",
                "Mode-B",
                "模板匹配 · " + methodTag,
                detail,
                new int[]{xF, yF, xF + wF, yF + hF},
                Math.round(tCore * 1000.0) / 1000.0,
                Math.round(fineCorr * 1000.0) / 1000.0,
                bestCorner,
                255,
                px0, py0, pw, ph,
                origPatch,
                cleanPatchArgb
        );
    }

    private static double[] searchRoiForTemplate(
            float[] roiGray, int rw, int rh,
            float[] tmpl, int tw0, int th0,
            int shortSide, int imgW
    ) {
        double aspect0 = th0 / (double) tw0;
        int maxRoi = Math.max(rh, rw);
        // 510px proxy keeps FFT width <= 512 (2x faster & half RAM vs 560->1024) while preserving sharp strokes
        double proxyScale = (maxRoi > 510) ? (510.0 / maxRoi) : 1.0;

        float[] roiP;
        int prw, prh;
        if (proxyScale < 1.0) {
            prw = Math.max(1, (int) (rw * proxyScale));
            prh = Math.max(1, (int) (rh * proxyScale));
            roiP = resizeArea(roiGray, rw, rh, prw, prh);
        } else {
            prw = rw;
            prh = rh;
            roiP = roiGray;
        }
        float[] gRoiP = sobelMagnitude(roiP, prw, prh);

        int wMin = Math.max(60, (int) (0.16 * shortSide));
        int wMax = Math.min(rw - 4, (int) Math.max(0.48 * shortSide, 0.38 * imgW));
        if (wMax <= wMin) {
            return null;
        }

        // Prepare FFT context once for coarse ROI gradient
        FftContext coarseCtx = prepareRoiFft(gRoiP, prw, prh);

        double bestCorr = -1.0;
        int bestW = 0, bestH = 0, bestX = 0, bestY = 0;

        int prevWFull = -1;
        double logMin = Math.log(wMin);
        double logMax = Math.log(wMax);
        for (int s = 0; s < 28; s++) {
            int wFull = (int) Math.exp(logMin + (logMax - logMin) * s / 27.0);
            if (wFull == prevWFull) continue;
            prevWFull = wFull;
            int hFull = Math.max(10, (int) Math.round(wFull * aspect0));
            int wp = Math.max(24, (int) Math.round(wFull * proxyScale));
            int hp = Math.max(6, (int) Math.round(hFull * proxyScale));
            if (wp >= prw - 2 || hp >= prh - 2) continue;

            float[] tP = (wp < tw0)
                    ? resizeArea(tmpl, tw0, th0, wp, hp)
                    : resizeCubic(tmpl, tw0, th0, wp, hp);
            float[] gTP = sobelMagnitude(tP, wp, hp);
            double[] match = matchTemplateFft(coarseCtx, gTP, wp, hp);
            if (match != null && match[0] > bestCorr) {
                bestCorr = match[0];
                bestW = wFull;
                bestH = hFull;
                bestX = (int) Math.round(match[1] / proxyScale);
                bestY = (int) Math.round(match[2] / proxyScale);
            }
        }

        if (bestCorr < 0.22 || bestW <= 0) {
            return null;
        }

        int padS = Math.max(16, (int) (0.08 * bestW));
        int sx0 = Math.max(0, bestX - padS);
        int sx1 = Math.min(rw, bestX + bestW + padS);
        int sy0 = Math.max(0, bestY - padS);
        int sy1 = Math.min(rh, bestY + bestH + padS);
        int subW = sx1 - sx0;
        int subH = sy1 - sy0;

        float[] subGray = new float[subW * subH];
        for (int y = 0; y < subH; y++) {
            System.arraycopy(roiGray, (sy0 + y) * rw + sx0, subGray, y * subW, subW);
        }
        // Cap Stage-2 FFT sub-patch width at 700px (~4x faster on 4K images); Stage-3 descent refines at 1:1 0.1px
        double fineScale = (subW > 700) ? (700.0 / subW) : 1.0;
        float[] fineGray;
        int fineSubW, fineSubH;
        if (fineScale < 1.0) {
            fineSubW = Math.max(32, (int) Math.round(subW * fineScale));
            fineSubH = Math.max(16, (int) Math.round(subH * fineScale));
            fineGray = resizeArea(subGray, subW, subH, fineSubW, fineSubH);
        } else {
            fineSubW = subW;
            fineSubH = subH;
            fineGray = subGray;
        }
        float[] gSub = sobelMagnitude(fineGray, fineSubW, fineSubH);
        FftContext fineCtx = prepareRoiFft(gSub, fineSubW, fineSubH);

        double fineBestCorr = -1.0;
        int fBestW = 0, fBestH = 0, fBestX = 0, fBestY = 0;
        double[] shRels = {0.98, 1.0, 1.02};
        for (int si = 0; si < 15; si++) {
            double sw = 0.93 + (1.07 - 0.93) * si / 14.0;
            int wfFull = (int) Math.round(bestW * sw);
            int wf = (int) Math.round(wfFull * fineScale);
            if (wf < 20 || wf >= fineSubW - 1) continue;
            for (double shRel : shRels) {
                int hfFull = (int) Math.round(wfFull * aspect0 * shRel);
                int hf = (int) Math.round(hfFull * fineScale);
                if (hf < 5 || hf >= fineSubH - 1) continue;
                float[] tF = (wf < tw0)
                        ? resizeArea(tmpl, tw0, th0, wf, hf)
                        : resizeCubic(tmpl, tw0, th0, wf, hf);
                float[] gTF = sobelMagnitude(tF, wf, hf);
                double[] match = matchTemplateFft(fineCtx, gTF, wf, hf);
                if (match != null && match[0] > fineBestCorr) {
                    fineBestCorr = match[0];
                    fBestW = wfFull;
                    fBestH = hfFull;
                    fBestX = sx0 + (int) Math.round(match[1] / fineScale);
                    fBestY = sy0 + (int) Math.round(match[2] / fineScale);
                }
            }
        }
        if (fineBestCorr < 0.0 || fBestW <= 0) {
            return null;
        }
        return new double[]{fineBestCorr, fBestW, fBestH, fBestX, fBestY};
    }

    private static double[] evalPatchLoss(
            float[] tmpl, int tw0, int th0, int pw, int ph,
            float[] obsT, int[] evalIndices,
            double ox, double oy, double sx, double sy, double sig
    ) {
        float[] m = renderTemplatePatchFast(tmpl, tw0, th0, pw, ph, ox, oy, sx, sy, sig);
        double num = 0.0;
        double den = 1e-8;
        for (int idx : evalIndices) {
            double mv = m[idx];
            num += mv * obsT[idx];
            den += mv * mv;
        }
        double tc = Math.max(0.20, Math.min(0.85, num / den));
        double sqErr = 0.0;
        for (int idx : evalIndices) {
            double d = obsT[idx] - tc * m[idx];
            sqErr += d * d;
        }
        return new double[]{sqErr / Math.max(1, evalIndices.length), tc};
    }

    private static float[] renderTemplatePatchFast(
            float[] tmpl, int tw0, int th0, int pw, int ph,
            double ox, double oy, double sx, double sy, double sig
    ) {
        float[] warped = new float[pw * ph];
        double invSx = 1.0 / sx;
        double invSy = 1.0 / sy;
        for (int py = 0; py < ph; py++) {
            double srcY = (py - oy) * invSy;
            if (srcY <= -1.0 || srcY >= th0) continue;
            for (int px = 0; px < pw; px++) {
                double srcX = (px - ox) * invSx;
                if (srcX <= -1.0 || srcX >= tw0) continue;
                warped[py * pw + px] = clamp(sampleBilinearZero(tmpl, tw0, th0, srcX, srcY), 0.0f, 1.0f);
            }
        }
        if (sig > 0.05) {
            int k = Math.max(3, ((int) Math.round(sig * 4.0)) * 2 + 1);
            warped = gaussianBlur(warped, pw, ph, k, sig);
            for (int i = 0; i < warped.length; i++) {
                warped[i] = clamp(warped[i], 0.0f, 1.0f);
            }
        }
        return warped;
    }

    private static float sampleBilinearZero(float[] src, int sw, int sh, double cx, double cy) {
        int x0 = (int) Math.floor(cx);
        int y0 = (int) Math.floor(cy);
        double fx = cx - x0;
        double fy = cy - y0;
        float v00 = (x0 >= 0 && x0 < sw && y0 >= 0 && y0 < sh) ? src[y0 * sw + x0] : 0.0f;
        float v10 = (x0 + 1 >= 0 && x0 + 1 < sw && y0 >= 0 && y0 < sh) ? src[y0 * sw + x0 + 1] : 0.0f;
        float v01 = (x0 >= 0 && x0 < sw && y0 + 1 >= 0 && y0 + 1 < sh) ? src[(y0 + 1) * sw + x0] : 0.0f;
        float v11 = (x0 + 1 >= 0 && x0 + 1 < sw && y0 + 1 >= 0 && y0 + 1 < sh) ? src[(y0 + 1) * sw + x0 + 1] : 0.0f;
        return (float) ((v00 * (1 - fx) + v10 * fx) * (1 - fy) + (v01 * (1 - fx) + v11 * fx) * fy);
    }

    private static float[] renderTemplatePatch(
            float[] tmpl, int tw0, int th0, int pw, int ph,
            double ox, double oy, double sx, double sy, double sig
    ) {
        float[] warped = new float[pw * ph];
        double invSx = 1.0 / sx;
        double invSy = 1.0 / sy;
        boolean areaDown = (sx < 0.9);
        for (int py = 0; py < ph; py++) {
            double srcY = (py - oy) * invSy;
            if (srcY <= -1.0 || srcY >= th0) continue;
            for (int px = 0; px < pw; px++) {
                double srcX = (px - ox) * invSx;
                if (srcX <= -1.0 || srcX >= tw0) continue;
                float v = areaDown
                        ? sampleAreaOrBilinearZero(tmpl, tw0, th0, srcX, srcY, invSx, invSy)
                        : sampleBicubicZero(tmpl, tw0, th0, srcX, srcY);
                warped[py * pw + px] = clamp(v, 0.0f, 1.0f);
            }
        }
        if (sig > 0.05) {
            int k = Math.max(3, ((int) Math.round(sig * 4.0)) * 2 + 1);
            warped = gaussianBlur(warped, pw, ph, k, sig);
            for (int i = 0; i < warped.length; i++) {
                warped[i] = clamp(warped[i], 0.0f, 1.0f);
            }
        }
        return warped;
    }

    // -------------------------------------------------------------------------
    // Image Resampling, Filtering, Sobel & Morphology
    // -------------------------------------------------------------------------

    private static float sampleAreaOrBilinearZero(
            float[] src, int sw, int sh, double cx, double cy, double rx, double ry
    ) {
        if (rx <= 1.05 && ry <= 1.05) {
            int x0 = (int) Math.floor(cx);
            int y0 = (int) Math.floor(cy);
            double fx = cx - x0;
            double fy = cy - y0;
            float v00 = (x0 >= 0 && x0 < sw && y0 >= 0 && y0 < sh) ? src[y0 * sw + x0] : 0.0f;
            float v10 = (x0 + 1 >= 0 && x0 + 1 < sw && y0 >= 0 && y0 < sh) ? src[y0 * sw + x0 + 1] : 0.0f;
            float v01 = (x0 >= 0 && x0 < sw && y0 + 1 >= 0 && y0 + 1 < sh) ? src[(y0 + 1) * sw + x0] : 0.0f;
            float v11 = (x0 + 1 >= 0 && x0 + 1 < sw && y0 + 1 >= 0 && y0 + 1 < sh) ? src[(y0 + 1) * sw + x0 + 1] : 0.0f;
            return (float) ((v00 * (1 - fx) + v10 * fx) * (1 - fy) + (v01 * (1 - fx) + v11 * fx) * fy);
        }
        double hx = 0.5 * rx;
        double hy = 0.5 * ry;
        double ax0 = cx - hx, ax1 = cx + hx;
        double ay0 = cy - hy, ay1 = cy + hy;
        int ix0 = Math.max(0, (int) Math.floor(ax0));
        int ix1 = Math.min(sw - 1, (int) Math.floor(ax1));
        int iy0 = Math.max(0, (int) Math.floor(ay0));
        int iy1 = Math.min(sh - 1, (int) Math.floor(ay1));
        double totalArea = (ax1 - ax0) * (ay1 - ay0);
        double sum = 0.0;
        for (int y = iy0; y <= iy1; y++) {
            double wy = Math.min(y + 1.0, ay1) - Math.max((double) y, ay0);
            if (wy <= 0.0) continue;
            int row = y * sw;
            for (int x = ix0; x <= ix1; x++) {
                double wx = Math.min(x + 1.0, ax1) - Math.max((double) x, ax0);
                if (wx > 0.0) {
                    sum += src[row + x] * wx * wy;
                }
            }
        }
        return (float) (sum / Math.max(1e-8, totalArea));
    }

    private static float sampleBicubicZero(float[] src, int sw, int sh, double sx, double sy) {
        int ix = (int) Math.floor(sx);
        int iy = (int) Math.floor(sy);
        double fx = sx - ix;
        double fy = sy - iy;
        double sum = 0.0;
        for (int m = -1; m <= 2; m++) {
            int y = iy + m;
            double wy = cubicWeight(fy - m);
            if (y < 0 || y >= sh) continue;
            int row = y * sw;
            for (int n = -1; n <= 2; n++) {
                int x = ix + n;
                if (x < 0 || x >= sw) continue;
                sum += src[row + x] * wy * cubicWeight(fx - n);
            }
        }
        return (float) sum;
    }

    private static double cubicWeight(double t) {
        double a = -0.75; // OpenCV INTER_CUBIC uses A = -0.75
        double x = Math.abs(t);
        if (x <= 1.0) {
            return ((a + 2.0) * x - (a + 3.0)) * x * x + 1.0;
        }
        if (x < 2.0) {
            return ((a * x - 5.0 * a) * x + 8.0 * a) * x - 4.0 * a;
        }
        return 0.0;
    }

    static float[] resizeArea(float[] src, int sw, int sh, int dw, int dh) {
        float[] dst = new float[dw * dh];
        double scaleX = sw / (double) dw;
        double scaleY = sh / (double) dh;
        for (int dy = 0; dy < dh; dy++) {
            double sy0 = dy * scaleY;
            double sy1 = (dy + 1) * scaleY;
            int iy0 = Math.max(0, (int) Math.floor(sy0));
            int iy1 = Math.min(sh - 1, (int) Math.floor(sy1));
            for (int dx = 0; dx < dw; dx++) {
                double sx0 = dx * scaleX;
                double sx1 = (dx + 1) * scaleX;
                int ix0 = Math.max(0, (int) Math.floor(sx0));
                int ix1 = Math.min(sw - 1, (int) Math.floor(sx1));
                double sum = 0.0;
                double area = (sx1 - sx0) * (sy1 - sy0);
                for (int y = iy0; y <= iy1; y++) {
                    double wy = Math.min(y + 1.0, sy1) - Math.max((double) y, sy0);
                    if (wy <= 0.0) continue;
                    int row = y * sw;
                    for (int x = ix0; x <= ix1; x++) {
                        double wx = Math.min(x + 1.0, sx1) - Math.max((double) x, sx0);
                        if (wx > 0.0) {
                            sum += src[row + x] * wx * wy;
                        }
                    }
                }
                dst[dy * dw + dx] = (float) (sum / Math.max(1e-8, area));
            }
        }
        return dst;
    }

    static float[] resizeCubic(float[] src, int sw, int sh, int dw, int dh) {
        float[] dst = new float[dw * dh];
        double scaleX = sw / (double) dw;
        double scaleY = sh / (double) dh;
        for (int dy = 0; dy < dh; dy++) {
            double sy = (dy + 0.5) * scaleY - 0.5;
            int iy = (int) Math.floor(sy);
            double fy = sy - iy;
            for (int dx = 0; dx < dw; dx++) {
                double sx = (dx + 0.5) * scaleX - 0.5;
                int ix = (int) Math.floor(sx);
                double fx = sx - ix;
                double sum = 0.0;
                for (int m = -1; m <= 2; m++) {
                    int y = reflect101(iy + m, sh);
                    double wy = cubicWeight(fy - m);
                    int row = y * sw;
                    for (int n = -1; n <= 2; n++) {
                        int x = reflect101(ix + n, sw);
                        sum += src[row + x] * wy * cubicWeight(fx - n);
                    }
                }
                dst[dy * dw + dx] = (float) sum;
            }
        }
        return dst;
    }

    private static int reflect101(int i, int n) {
        if (n <= 1) return 0;
        while (i < 0 || i >= n) {
            if (i < 0) i = -i;
            if (i >= n) i = 2 * n - 2 - i;
        }
        return i;
    }

    private static float[] sobelMagnitude(float[] img, int w, int h) {
        float[] mag = new float[w * h];
        for (int y = 0; y < h; y++) {
            int ym = reflect101(y - 1, h);
            int y0 = y;
            int yp = reflect101(y + 1, h);
            int rowM = ym * w;
            int row0 = y0 * w;
            int rowP = yp * w;
            for (int x = 0; x < w; x++) {
                int xm = reflect101(x - 1, w);
                int xp = reflect101(x + 1, w);
                float p00 = img[rowM + xm], p01 = img[rowM + x], p02 = img[rowM + xp];
                float p10 = img[row0 + xm],                      p12 = img[row0 + xp];
                float p20 = img[rowP + xm], p21 = img[rowP + x], p22 = img[rowP + xp];
                float gx = (p02 - p00) + 2.0f * (p12 - p10) + (p22 - p20);
                float gy = (p20 + 2.0f * p21 + p22) - (p00 + 2.0f * p01 + p02);
                mag[row0 + x] = (float) Math.hypot(gx, gy);
            }
        }
        return mag;
    }

    private static float[] gaussianBlur(float[] src, int w, int h, int ksize, double sigma) {
        int rad = ksize / 2;
        float[] kernel = new float[ksize];
        double sumK = 0.0;
        double s2 = 2.0 * sigma * sigma;
        for (int i = -rad; i <= rad; i++) {
            double v = Math.exp(-(i * i) / s2);
            kernel[i + rad] = (float) v;
            sumK += v;
        }
        for (int i = 0; i < ksize; i++) {
            kernel[i] /= (float) sumK;
        }
        float[] tmp = new float[w * h];
        float[] dst = new float[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                float acc = 0.0f;
                for (int k = -rad; k <= rad; k++) {
                    int sx = reflect101(x + k, w);
                    acc += src[row + sx] * kernel[k + rad];
                }
                tmp[row + x] = acc;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float acc = 0.0f;
                for (int k = -rad; k <= rad; k++) {
                    int sy = reflect101(y + k, h);
                    acc += tmp[sy * w + x] * kernel[k + rad];
                }
                dst[y * w + x] = acc;
            }
        }
        return dst;
    }

    private static boolean[] dilateBox(boolean[] mask, int w, int h, int k) {
        int rad = k / 2;
        boolean[] tmp = new boolean[w * h];
        boolean[] out = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                boolean any = false;
                int xStart = Math.max(0, x - rad);
                int xEnd = Math.min(w - 1, x + rad);
                for (int sx = xStart; sx <= xEnd; sx++) {
                    if (mask[row + sx]) {
                        any = true;
                        break;
                    }
                }
                tmp[row + x] = any;
            }
        }
        for (int y = 0; y < h; y++) {
            int yStart = Math.max(0, y - rad);
            int yEnd = Math.min(h - 1, y + rad);
            for (int x = 0; x < w; x++) {
                boolean any = false;
                for (int sy = yStart; sy <= yEnd; sy++) {
                    if (tmp[sy * w + x]) {
                        any = true;
                        break;
                    }
                }
                out[y * w + x] = any;
            }
        }
        return out;
    }

    private static boolean[] erodeBox(boolean[] mask, int w, int h, int k) {
        int rad = k / 2;
        boolean[] tmp = new boolean[w * h];
        boolean[] out = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                boolean all = true;
                int xStart = Math.max(0, x - rad);
                int xEnd = Math.min(w - 1, x + rad);
                for (int sx = xStart; sx <= xEnd; sx++) {
                    if (!mask[row + sx]) {
                        all = false;
                        break;
                    }
                }
                tmp[row + x] = all;
            }
        }
        for (int y = 0; y < h; y++) {
            int yStart = Math.max(0, y - rad);
            int yEnd = Math.min(h - 1, y + rad);
            for (int x = 0; x < w; x++) {
                boolean all = true;
                for (int sy = yStart; sy <= yEnd; sy++) {
                    if (!tmp[sy * w + x]) {
                        all = false;
                        break;
                    }
                }
                out[y * w + x] = all;
            }
        }
        return out;
    }

    private static int[] median5x5OnMask(int[] rgbInt, int w, int h, boolean[] mask) {
        int[] out = Arrays.copyOf(rgbInt, rgbInt.length);
        int[] win = new int[25];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (!mask[idx]) continue;
                for (int c = 0; c < 3; c++) {
                    int k = 0;
                    for (int dy = -2; dy <= 2; dy++) {
                        int yy = clampInt(y + dy, 0, h - 1);
                        for (int dx = -2; dx <= 2; dx++) {
                            int xx = clampInt(x + dx, 0, w - 1);
                            win[k++] = rgbInt[(yy * w + xx) * 3 + c];
                        }
                    }
                    Arrays.sort(win);
                    out[idx * 3 + c] = win[12];
                }
            }
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Exact 2D Euclidean Distance Transform (Felzenszwalb-Huttenlocher)
    // Computes distance from every pixel to the nearest TRUE pixel in `seeds`.
    // -------------------------------------------------------------------------

    private static float[] distanceTransformL2(boolean[] seeds, int w, int h) {
        final float INF = 1e20f;
        float[] d = new float[w * h];
        for (int i = 0; i < d.length; i++) {
            d[i] = seeds[i] ? 0.0f : INF;
        }
        float[] f = new float[Math.max(w, h)];
        float[] z = new float[Math.max(w, h) + 1];
        int[] v = new int[Math.max(w, h)];

        // Vertical pass
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) f[y] = d[y * w + x];
            dt1d(f, h, v, z);
            for (int y = 0; y < h; y++) d[y * w + x] = f[y];
        }
        // Horizontal pass
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) f[x] = d[row + x];
            dt1d(f, w, v, z);
            for (int x = 0; x < w; x++) d[row + x] = (float) Math.sqrt(f[x]);
        }
        return d;
    }

    private static void dt1d(float[] f, int n, int[] v, float[] z) {
        int k = 0;
        v[0] = 0;
        z[0] = Float.NEGATIVE_INFINITY;
        z[1] = Float.POSITIVE_INFINITY;
        for (int q = 1; q < n; q++) {
            if (f[q] >= 1e19f) continue;
            float s;
            while (true) {
                int vk = v[k];
                if (f[vk] >= 1e19f) {
                    s = Float.NEGATIVE_INFINITY;
                } else {
                    s = ((f[q] + q * (float) q) - (f[vk] + vk * (float) vk)) / (2.0f * (q - vk));
                }
                if (s > z[k]) break;
                if (k == 0) break;
                k--;
            }
            if (f[v[k]] >= 1e19f) {
                v[0] = q;
                z[0] = Float.NEGATIVE_INFINITY;
                z[1] = Float.POSITIVE_INFINITY;
            } else {
                k++;
                v[k] = q;
                z[k] = s;
                z[k + 1] = Float.POSITIVE_INFINITY;
            }
        }
        if (f[v[0]] >= 1e19f) {
            return;
        }
        float[] out = new float[n];
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) k++;
            float dq = q - v[k];
            out[q] = dq * dq + f[v[k]];
        }
        System.arraycopy(out, 0, f, 0, n);
    }

    // -------------------------------------------------------------------------
    // Exact 2D FFT Normalized Cross-Correlation (TM_CCOEFF_NORMED)
    // -------------------------------------------------------------------------

    private static final class FftContext {
        final int w;
        final int h;
        final int fftW;
        final int fftH;
        final double[] gFftRe;
        final double[] gFftIm;
        final double[] sumG;
        final double[] sumG2;
        final int stride;

        FftContext(int w, int h, int fftW, int fftH,
                   double[] gFftRe, double[] gFftIm, double[] sumG, double[] sumG2) {
            this.w = w;
            this.h = h;
            this.fftW = fftW;
            this.fftH = fftH;
            this.gFftRe = gFftRe;
            this.gFftIm = gFftIm;
            this.sumG = sumG;
            this.sumG2 = sumG2;
            this.stride = w + 1;
        }
    }

    private static int nextPow2(int v) {
        int p = 1;
        while (p < v) p <<= 1;
        return p;
    }

    private static FftContext prepareRoiFft(float[] g, int w, int h) {
        int fftW = nextPow2(w);
        int fftH = nextPow2(h);
        int nFft = fftW * fftH;
        double[] re = new double[nFft];
        double[] im = new double[nFft];
        int stride = w + 1;
        double[] sumG = new double[(w + 1) * (h + 1)];
        double[] sumG2 = new double[(w + 1) * (h + 1)];

        for (int y = 0; y < h; y++) {
            double r1 = 0.0, r2 = 0.0;
            int gRow = y * w;
            int fRow = y * fftW;
            int pRow = (y + 1) * stride;
            int prevRow = y * stride;
            for (int x = 0; x < w; x++) {
                double v = g[gRow + x];
                re[fRow + x] = v;
                r1 += v;
                r2 += v * v;
                sumG[pRow + x + 1] = sumG[prevRow + x + 1] + r1;
                sumG2[pRow + x + 1] = sumG2[prevRow + x + 1] + r2;
            }
        }
        fft2d(re, im, fftW, fftH, false);
        return new FftContext(w, h, fftW, fftH, re, im, sumG, sumG2);
    }

    private static double[] matchTemplateFft(FftContext ctx, float[] t, int tw, int th) {
        int outW = ctx.w - tw + 1;
        int outH = ctx.h - th + 1;
        if (outW <= 0 || outH <= 0) return null;

        int tmplN = tw * th;
        double sumT = 0.0;
        for (int i = 0; i < tmplN; i++) sumT += t[i];
        double meanT = sumT / tmplN;
        double normT2 = 0.0;

        int fftW = ctx.fftW;
        int fftH = ctx.fftH;
        int nFft = fftW * fftH;
        double[] tRe = new double[nFft];
        double[] tIm = new double[nFft];
        for (int y = 0; y < th; y++) {
            int srcRow = y * tw;
            int dstRow = y * fftW;
            for (int x = 0; x < tw; x++) {
                double z = t[srcRow + x] - meanT;
                tRe[dstRow + x] = z;
                normT2 += z * z;
            }
        }
        double normT = Math.sqrt(normT2);
        if (normT <= 1e-8) return null;

        fft2d(tRe, tIm, fftW, fftH, false);

        // Cross-correlation: IFFT( FFT(G) * conj(FFT(T)) )
        for (int i = 0; i < nFft; i++) {
            double gr = ctx.gFftRe[i];
            double gi = ctx.gFftIm[i];
            double tr = tRe[i];
            double ti = -tIm[i];
            tRe[i] = gr * tr - gi * ti;
            tIm[i] = gr * ti + gi * tr;
        }
        fft2d(tRe, tIm, fftW, fftH, true);

        double invN = 1.0 / tmplN;
        double maxVal = -1e9;
        int maxX = 0;
        int maxY = 0;
        int stride = ctx.stride;

        for (int y = 0; y < outH; y++) {
            int cRow = y * fftW;
            for (int x = 0; x < outW; x++) {
                double sG = boxSum(ctx.sumG, stride, x, y, x + tw, y + th);
                double sG2 = boxSum(ctx.sumG2, stride, x, y, x + tw, y + th);
                double varG = sG2 - sG * sG * invN;
                if (varG <= 1e-8) continue;
                double denom = normT * Math.sqrt(varG);
                double corr = tRe[cRow + x] / denom;
                if (corr > maxVal) {
                    maxVal = corr;
                    maxX = x;
                    maxY = y;
                }
            }
        }
        return new double[]{maxVal, maxX, maxY};
    }

    private static void fft2d(double[] re, double[] im, int w, int h, boolean invert) {
        double[] colRe = new double[h];
        double[] colIm = new double[h];
        for (int y = 0; y < h; y++) {
            fft1d(re, im, y * w, 1, w, invert);
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                colRe[y] = re[y * w + x];
                colIm[y] = im[y * w + x];
            }
            fft1d(colRe, colIm, 0, 1, h, invert);
            for (int y = 0; y < h; y++) {
                re[y * w + x] = colRe[y];
                im[y * w + x] = colIm[y];
            }
        }
    }

    private static void fft1d(double[] re, double[] im, int off, int step, int n, boolean invert) {
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                int ii = off + i * step;
                int jj = off + j * step;
                double tr = re[ii]; re[ii] = re[jj]; re[jj] = tr;
                double ti = im[ii]; im[ii] = im[jj]; im[jj] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2.0 * Math.PI / len * (invert ? 1.0 : -1.0);
            double wlenRe = Math.cos(ang);
            double wlenIm = Math.sin(ang);
            int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                double wRe = 1.0;
                double wIm = 0.0;
                for (int j = 0; j < half; j++) {
                    int idx1 = off + (i + j) * step;
                    int idx2 = off + (i + j + half) * step;
                    double uRe = re[idx1], uIm = im[idx1];
                    double vRe = re[idx2] * wRe - im[idx2] * wIm;
                    double vIm = re[idx2] * wIm + im[idx2] * wRe;
                    re[idx1] = uRe + vRe;
                    im[idx1] = uIm + vIm;
                    re[idx2] = uRe - vRe;
                    im[idx2] = uIm - vIm;
                    double nwRe = wRe * wlenRe - wIm * wlenIm;
                    wIm = wRe * wlenIm + wIm * wlenRe;
                    wRe = nwRe;
                }
            }
        }
        if (invert) {
            double invN = 1.0 / n;
            for (int i = 0; i < n; i++) {
                int idx = off + i * step;
                re[idx] *= invN;
                im[idx] *= invN;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Telea (2004) Fast Marching Method Inpainting (cv2.INPAINT_TELEA)
    // -------------------------------------------------------------------------

    private static int[] teleaInpaint(int[] rgbInt, int w, int h, boolean[] mask, int radius) {
        int n = w * h;
        int[] out = Arrays.copyOf(rgbInt, rgbInt.length);
        // flags: 0 = KNOWN, 1 = BAND, 2 = INSIDE
        byte[] flag = new byte[n];
        float[] dist = new float[n];
        final float INF = 1e6f;

        boolean anyMask = false;
        for (int i = 0; i < n; i++) {
            if (mask[i]) {
                flag[i] = 2;
                dist[i] = INF;
                anyMask = true;
            } else {
                flag[i] = 0;
                dist[i] = 0.0f;
            }
        }
        if (!anyMask) return out;

        // Binary min-heap on pixel index ordered by dist[idx]; allocate 4x margin for duplicate relaxations
        int cap = Math.max(64, n * 4 + 16);
        int[] heapIdx = new int[cap];
        float[] heapDist = new float[cap];
        int[] heapSize = {0};

        // Initial narrow band: INSIDE pixels adjacent to KNOWN
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (flag[idx] != 2) continue;
                boolean adjKnown = (x > 0 && flag[idx - 1] == 0)
                        || (x + 1 < w && flag[idx + 1] == 0)
                        || (y > 0 && flag[idx - w] == 0)
                        || (y + 1 < h && flag[idx + w] == 0);
                if (adjKnown) {
                    flag[idx] = 1;
                    dist[idx] = 1.0f;
                    heapPush(heapIdx, heapDist, heapSize, idx, 1.0f);
                }
            }
        }

        int radSq = radius * radius;
        while (heapSize[0] > 0) {
            int idx = heapPop(heapIdx, heapDist, heapSize);
            if (flag[idx] == 0) continue; // already finalized
            int y = idx / w;
            int x = idx % w;

            // Inpaint current BAND pixel from KNOWN neighbors within radius, then finalize KNOWN
            inpaintSinglePixel(out, flag, dist, w, h, x, y, radius, radSq);
            flag[idx] = 0;

            int[] nx = {x - 1, x + 1, x, x};
            int[] ny = {y, y, y - 1, y + 1};
            for (int k = 0; k < 4; k++) {
                int xx = nx[k], yy = ny[k];
                if (xx < 0 || xx >= w || yy < 0 || yy >= h) continue;
                int nIdx = yy * w + xx;
                if (flag[nIdx] != 0) {
                    float dNew = Math.min(
                            solveEikonal(xx - 1, yy, xx, yy - 1, w, h, flag, dist),
                            Math.min(
                                    solveEikonal(xx + 1, yy, xx, yy - 1, w, h, flag, dist),
                                    Math.min(
                                            solveEikonal(xx - 1, yy, xx, yy + 1, w, h, flag, dist),
                                            solveEikonal(xx + 1, yy, xx, yy + 1, w, h, flag, dist)
                                    )
                            )
                    );
                    if (dNew < dist[nIdx]) {
                        dist[nIdx] = dNew;
                        flag[nIdx] = 1;
                        heapPush(heapIdx, heapDist, heapSize, nIdx, dNew);
                    }
                }
            }
        }
        return out;
    }

    private static float solveEikonal(
            int x1, int y1, int x2, int y2,
            int w, int h, byte[] flag, float[] dist
    ) {
        final float INF = 1e6f;
        boolean ok1 = (x1 >= 0 && x1 < w && y1 >= 0 && y1 < h && flag[y1 * w + x1] == 0);
        boolean ok2 = (x2 >= 0 && x2 < w && y2 >= 0 && y2 < h && flag[y2 * w + x2] == 0);
        if (ok1 && ok2) {
            float d1 = dist[y1 * w + x1];
            float d2 = dist[y2 * w + x2];
            float d = Math.abs(d1 - d2);
            if (d >= 1.0f) {
                return Math.min(d1, d2) + 1.0f;
            }
            return 0.5f * (d1 + d2 + (float) Math.sqrt(Math.max(0.0f, 2.0f - d * d)));
        }
        if (ok1) return dist[y1 * w + x1] + 1.0f;
        if (ok2) return dist[y2 * w + x2] + 1.0f;
        return INF;
    }

    private static void inpaintSinglePixel(
            int[] img, byte[] flag, float[] dist,
            int w, int h, int x, int y, int radius, int radSq
    ) {
        // Compute distance gradient (gradT) at (x, y)
        float gradTx = 0.0f;
        float gradTy = 0.0f;
        boolean leftK = (x > 0 && flag[y * w + (x - 1)] != 2);
        boolean rightK = (x + 1 < w && flag[y * w + (x + 1)] != 2);
        if (leftK && rightK) {
            gradTx = 0.5f * (dist[y * w + (x + 1)] - dist[y * w + (x - 1)]);
        } else if (leftK) {
            gradTx = dist[y * w + x] - dist[y * w + (x - 1)];
        } else if (rightK) {
            gradTx = dist[y * w + (x + 1)] - dist[y * w + x];
        }

        boolean upK = (y > 0 && flag[(y - 1) * w + x] != 2);
        boolean downK = (y + 1 < h && flag[(y + 1) * w + x] != 2);
        if (upK && downK) {
            gradTy = 0.5f * (dist[(y + 1) * w + x] - dist[(y - 1) * w + x]);
        } else if (upK) {
            gradTy = dist[y * w + x] - dist[(y - 1) * w + x];
        } else if (downK) {
            gradTy = dist[(y + 1) * w + x] - dist[y * w + x];
        }

        double sumR = 0.0, sumG = 0.0, sumB = 0.0, sumW = 0.0;
        double sumGXR = 0.0, sumGXG = 0.0, sumGXB = 0.0;
        double fallbackR = 0.0, fallbackG = 0.0, fallbackB = 0.0;
        int fbCount = 0;

        int y0 = Math.max(0, y - radius), y1 = Math.min(h - 1, y + radius);
        int x0 = Math.max(0, x - radius), x1 = Math.min(w - 1, x + radius);
        float dCenter = dist[y * w + x];

        for (int yy = y0; yy <= y1; yy++) {
            int dy = y - yy;
            int dy2 = dy * dy;
            int row = yy * w;
            for (int xx = x0; xx <= x1; xx++) {
                int dx = x - xx;
                int r2 = dx * dx + dy2;
                if (r2 == 0 || r2 > radSq) continue;
                int nIdx = row + xx;
                if (flag[nIdx] != 0) continue;

                int pr = img[nIdx * 3];
                int pg = img[nIdx * 3 + 1];
                int pb = img[nIdx * 3 + 2];
                fallbackR += pr;
                fallbackG += pg;
                fallbackB += pb;
                fbCount++;

                double dst = 1.0 / (r2 * Math.sqrt(r2));
                double lev = 1.0 / (1.0 + Math.abs(dist[nIdx] - dCenter));
                double dir = Math.abs(dx * gradTx + dy * gradTy);
                if (dir < 1e-6) dir = 1e-6;
                double weight = dir * dst * lev;

                // Image gradient at neighbor if symmetric KNOWN neighbors exist
                float ixR = 0.0f, ixG = 0.0f, ixB = 0.0f;
                if (xx > 0 && xx + 1 < w && flag[nIdx - 1] == 0 && flag[nIdx + 1] == 0) {
                    ixR = 0.5f * (img[(nIdx + 1) * 3] - img[(nIdx - 1) * 3]);
                    ixG = 0.5f * (img[(nIdx + 1) * 3 + 1] - img[(nIdx - 1) * 3 + 1]);
                    ixB = 0.5f * (img[(nIdx + 1) * 3 + 2] - img[(nIdx - 1) * 3 + 2]);
                }
                float iyR = 0.0f, iyG = 0.0f, iyB = 0.0f;
                if (yy > 0 && yy + 1 < h && flag[nIdx - w] == 0 && flag[nIdx + w] == 0) {
                    iyR = 0.5f * (img[(nIdx + w) * 3] - img[(nIdx - w) * 3]);
                    iyG = 0.5f * (img[(nIdx + w) * 3 + 1] - img[(nIdx - w) * 3 + 1]);
                    iyB = 0.5f * (img[(nIdx + w) * 3 + 2] - img[(nIdx - w) * 3 + 2]);
                }

                sumR += weight * pr;
                sumG += weight * pg;
                sumB += weight * pb;
                sumGXR += weight * (dx * ixR + dy * iyR);
                sumGXG += weight * (dx * ixG + dy * iyG);
                sumGXB += weight * (dx * ixB + dy * iyB);
                sumW += weight;
            }
        }

        int outIdx = (y * w + x) * 3;
        if (sumW > 1e-9) {
            img[outIdx] = clampInt((int) Math.round((sumR + sumGXR) / sumW), 0, 255);
            img[outIdx + 1] = clampInt((int) Math.round((sumG + sumGXG) / sumW), 0, 255);
            img[outIdx + 2] = clampInt((int) Math.round((sumB + sumGXB) / sumW), 0, 255);
        } else if (fbCount > 0) {
            img[outIdx] = clampInt((int) Math.round(fallbackR / fbCount), 0, 255);
            img[outIdx + 1] = clampInt((int) Math.round(fallbackG / fbCount), 0, 255);
            img[outIdx + 2] = clampInt((int) Math.round(fallbackB / fbCount), 0, 255);
        }
    }

    private static void heapPush(int[] heapIdx, float[] heapDist, int[] sizeRef, int idx, float d) {
        int i = sizeRef[0]++;
        heapIdx[i] = idx;
        heapDist[i] = d;
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (heapDist[p] <= heapDist[i]) break;
            int ti = heapIdx[p]; heapIdx[p] = heapIdx[i]; heapIdx[i] = ti;
            float td = heapDist[p]; heapDist[p] = heapDist[i]; heapDist[i] = td;
            i = p;
        }
    }

    private static int heapPop(int[] heapIdx, float[] heapDist, int[] sizeRef) {
        int res = heapIdx[0];
        int n = --sizeRef[0];
        if (n > 0) {
            heapIdx[0] = heapIdx[n];
            heapDist[0] = heapDist[n];
            int i = 0;
            while (true) {
                int l = (i << 1) + 1;
                if (l >= n) break;
                int r = l + 1;
                int c = (r < n && heapDist[r] < heapDist[l]) ? r : l;
                if (heapDist[i] <= heapDist[c]) break;
                int ti = heapIdx[i]; heapIdx[i] = heapIdx[c]; heapIdx[c] = ti;
                float td = heapDist[i]; heapDist[i] = heapDist[c]; heapDist[c] = td;
                i = c;
            }
        }
        return res;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
