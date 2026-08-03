/**
 * Signature image processing — PURE pixel math shared by every SignatureCapture mode (§3, "Signature
 * capture standard"). All functions operate on a plain {@link RasterImage} (structurally an `ImageData`:
 * `{ data, width, height }`), so they run in the browser canvas AND in Node/vitest with no DOM. The output
 * of every path is a dark-ink-on-white raster at the STANDARD dimensions — identical in format to the draw
 * pad, so storage + the PDF stamp can't tell which mode produced it.
 */

export const SIGNATURE_WIDTH = 520;
export const SIGNATURE_HEIGHT = 170;

/** Default ink — the theme-invariant `--signature-ink` (#111827 ≈ hsl(222 47% 11%)); overridable by the UI. */
export const DEFAULT_INK: [number, number, number] = [17, 24, 39];
const PAD = 14; // white margin kept around the fitted ink

export interface RasterImage {
  data: Uint8ClampedArray;
  width: number;
  height: number;
}

/** An all-white RGBA raster of the given size. */
export function whiteRaster(width: number, height: number): RasterImage {
  const data = new Uint8ClampedArray(width * height * 4).fill(255);
  return { data, width, height };
}

/** Composite the source over white (flatten alpha) and reduce to an 8-bit luminance buffer, 0=black. */
export function toGrayOnWhite(src: RasterImage): Uint8Array {
  const { data, width, height } = src;
  const gray = new Uint8Array(width * height);
  for (let p = 0, i = 0; p < gray.length; p++, i += 4) {
    const a = data[i + 3] / 255;
    const r = data[i] * a + 255 * (1 - a);
    const g = data[i + 1] * a + 255 * (1 - a);
    const b = data[i + 2] * a + 255 * (1 - a);
    gray[p] = Math.round(0.299 * r + 0.587 * g + 0.114 * b);
  }
  return gray;
}

/** Summed-area table of a grayscale buffer, for O(1) box means. Size (w+1)*(h+1). */
function integralImage(gray: Uint8Array, w: number, h: number): Float64Array {
  const integ = new Float64Array((w + 1) * (h + 1));
  for (let y = 0; y < h; y++) {
    let rowSum = 0;
    for (let x = 0; x < w; x++) {
      rowSum += gray[y * w + x];
      integ[(y + 1) * (w + 1) + (x + 1)] = integ[y * (w + 1) + (x + 1)] + rowSum;
    }
  }
  return integ;
}

function boxSum(integ: Float64Array, w: number, x0: number, y0: number, x1: number, y1: number): number {
  const s = w + 1;
  return (
    integ[(y1 + 1) * s + (x1 + 1)] -
    integ[y0 * s + (x1 + 1)] -
    integ[(y1 + 1) * s + x0] +
    integ[y0 * s + x0]
  );
}

/**
 * ADAPTIVE (local-mean) threshold → an ink mask (1 = ink). Comparing each pixel to the mean of its local
 * window handles UNEVEN lighting/gradients that a single global threshold clips. `bias` (0..~40) is the
 * darkness a pixel must beat its local mean by to count as ink — the UI's sensitivity control: lower = more
 * sensitive (faint ink, more noise), higher = stricter (only bold strokes).
 */
export function adaptiveInkMask(gray: Uint8Array, w: number, h: number, bias: number): Uint8Array {
  const integ = integralImage(gray, w, h);
  const radius = Math.max(4, Math.round(Math.min(w, h) / 12));
  const mask = new Uint8Array(w * h);
  for (let y = 0; y < h; y++) {
    const y0 = Math.max(0, y - radius);
    const y1 = Math.min(h - 1, y + radius);
    for (let x = 0; x < w; x++) {
      const x0 = Math.max(0, x - radius);
      const x1 = Math.min(w - 1, x + radius);
      const area = (x1 - x0 + 1) * (y1 - y0 + 1);
      const mean = boxSum(integ, w, x0, y0, x1, y1) / area;
      mask[y * w + x] = gray[y * w + x] < mean - bias ? 1 : 0;
    }
  }
  return mask;
}

/** Drop ink pixels with fewer than {@code minNeighbors} ink neighbours (8-connected) — removes speckle. */
export function despeckle(mask: Uint8Array, w: number, h: number, minNeighbors = 2): Uint8Array {
  const out = new Uint8Array(mask.length);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      if (!mask[y * w + x]) continue;
      let n = 0;
      for (let dy = -1; dy <= 1; dy++) {
        for (let dx = -1; dx <= 1; dx++) {
          if (dx === 0 && dy === 0) continue;
          const nx = x + dx;
          const ny = y + dy;
          if (nx >= 0 && nx < w && ny >= 0 && ny < h && mask[ny * w + nx]) n++;
        }
      }
      if (n >= minNeighbors) out[y * w + x] = 1;
    }
  }
  return out;
}

export interface Bounds {
  x0: number;
  y0: number;
  x1: number;
  y1: number;
}

/** Tight bounding box of the pixels where {@code test(index)} is true, or null if none. */
export function boundsWhere(test: (i: number) => boolean, w: number, h: number): Bounds | null {
  let x0 = w;
  let y0 = h;
  let x1 = -1;
  let y1 = -1;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      if (test(y * w + x)) {
        if (x < x0) x0 = x;
        if (x > x1) x1 = x;
        if (y < y0) y0 = y;
        if (y > y1) y1 = y;
      }
    }
  }
  return x1 < 0 ? null : { x0, y0, x1, y1 };
}

/** The scale + centred offset that fits a {@code bw×bh} box into {@code tw×th} (with PAD), never upscaling past 1×. */
export function fitRect(bw: number, bh: number, tw: number, th: number) {
  const innerW = tw - PAD * 2;
  const innerH = th - PAD * 2;
  const scale = Math.min(innerW / bw, innerH / bh, 1);
  const dw = bw * scale;
  const dh = bh * scale;
  return { scale, dx: (tw - dw) / 2, dy: (th - dh) / 2, dw, dh };
}

/** Sample the value of {@code sample(sx, sy)} at each target pixel of a fitted region (nearest-neighbour). */
function paintFitted(
  bounds: Bounds,
  target: RasterImage,
  sample: (sx: number, sy: number) => [number, number, number] | null,
): void {
  const bw = bounds.x1 - bounds.x0 + 1;
  const bh = bounds.y1 - bounds.y0 + 1;
  const { scale, dx, dy, dw, dh } = fitRect(bw, bh, target.width, target.height);
  for (let ty = 0; ty < dh; ty++) {
    for (let tx = 0; tx < dw; tx++) {
      const sx = bounds.x0 + Math.min(bw - 1, Math.floor(tx / scale));
      const sy = bounds.y0 + Math.min(bh - 1, Math.floor(ty / scale));
      const rgb = sample(sx, sy);
      if (!rgb) continue;
      const px = Math.round(dx + tx);
      const py = Math.round(dy + ty);
      if (px < 0 || px >= target.width || py < 0 || py >= target.height) continue;
      const di = (py * target.width + px) * 4;
      target.data[di] = rgb[0];
      target.data[di + 1] = rgb[1];
      target.data[di + 2] = rgb[2];
      target.data[di + 3] = 255;
    }
  }
}

/**
 * UPLOAD & CLEAN: grayscale → adaptive threshold (sensitivity → bias) → despeckle → crop to ink → composite
 * as PURE {@code ink} on white at the standard size. {@code sensitivity} 0..1 (0.5 default): higher keeps
 * more faint ink.
 */
export function cleanToStandard(
  src: RasterImage,
  sensitivity = 0.5,
  ink: [number, number, number] = DEFAULT_INK,
): RasterImage {
  const gray = toGrayOnWhite(src);
  // sensitivity 1 → bias ~4 (very sensitive), 0 → bias ~34 (strict). Clamp to a sane band.
  const bias = Math.round(34 - sensitivity * 30);
  let mask = adaptiveInkMask(gray, src.width, src.height, bias);
  mask = despeckle(mask, src.width, src.height, 2);
  const b = boundsWhere((i) => mask[i] === 1, src.width, src.height);
  const out = whiteRaster(SIGNATURE_WIDTH, SIGNATURE_HEIGHT);
  if (!b) return out;
  paintFitted(b, out, (sx, sy) => (mask[sy * src.width + sx] ? ink : null));
  return out;
}

/**
 * UPLOAD (trusted): light white-flatten + crop to ink + fit onto the standard size, keeping the ink's own
 * (dark) grayscale AS-IS — a plain upload is trusted to already be a clean signature. Anything at least
 * slightly darker than white counts as ink for the crop.
 */
export function normalizeToStandard(src: RasterImage): RasterImage {
  const gray = toGrayOnWhite(src);
  const INK_MAX = 210; // <= this luminance is "ink" for cropping
  const b = boundsWhere((i) => gray[i] <= INK_MAX, src.width, src.height);
  const out = whiteRaster(SIGNATURE_WIDTH, SIGNATURE_HEIGHT);
  if (!b) return out;
  paintFitted(b, out, (sx, sy) => {
    const v = gray[sy * src.width + sx];
    return v <= INK_MAX ? [v, v, v] : null; // keep the dark stroke value; leave paper white
  });
  return out;
}

/**
 * The largest font size (≤ {@code maxPx}, ≥ {@code minPx}) whose rendered {@code text} width fits {@code
 * maxWidth}, using an injected {@code measureAtPx} (canvas measureText in the browser, a stub in tests).
 * Assumes width scales ~linearly with px — refined by a couple of guarded steps.
 */
export function fitFontPx(
  measureAtPx: (px: number) => number,
  maxWidth: number,
  maxPx: number,
  minPx = 16,
): number {
  const wAtMax = measureAtPx(maxPx);
  if (wAtMax <= maxWidth || wAtMax <= 0) return maxPx;
  let px = Math.max(minPx, Math.floor((maxPx * maxWidth) / wAtMax));
  // Shrink until it fits (guard against measure non-linearity), then it's the answer.
  while (px > minPx && measureAtPx(px) > maxWidth) px -= 1;
  return px;
}
