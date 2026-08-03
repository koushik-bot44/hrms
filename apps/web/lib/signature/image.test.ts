import { describe, it, expect } from 'vitest';
import {
  SIGNATURE_WIDTH,
  SIGNATURE_HEIGHT,
  cleanToStandard,
  normalizeToStandard,
  fitFontPx,
  type RasterImage,
} from './image';

// --- fixtures -------------------------------------------------------------

function raster(width: number, height: number, luminance = 255): RasterImage {
  const data = new Uint8ClampedArray(width * height * 4);
  for (let p = 0; p < width * height; p++) {
    const i = p * 4;
    data[i] = data[i + 1] = data[i + 2] = luminance;
    data[i + 3] = 255;
  }
  return { data, width, height };
}

function set(img: RasterImage, x: number, y: number, v: number): void {
  if (x < 0 || x >= img.width || y < 0 || y >= img.height) return;
  const i = (y * img.width + x) * 4;
  img.data[i] = img.data[i + 1] = img.data[i + 2] = v;
  img.data[i + 3] = 255;
}

function isWhite(img: RasterImage, x: number, y: number): boolean {
  const i = (y * img.width + x) * 4;
  return img.data[i] > 240 && img.data[i + 1] > 240 && img.data[i + 2] > 240;
}

function darkPixelCount(img: RasterImage): number {
  let n = 0;
  for (let p = 0; p < img.width * img.height; p++) {
    const i = p * 4;
    if (img.data[i] < 90 && img.data[i + 1] < 90 && img.data[i + 2] < 90) n++;
  }
  return n;
}

/** A dark stroke drawn over a left→right gray gradient, plus a few isolated speckle pixels. */
function noisyGradientSignature(): RasterImage {
  const w = 200;
  const h = 100;
  const img = raster(w, h);
  // Uneven lighting: a gradient background (light on the left, darker on the right).
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      set(img, x, y, Math.round(232 - (x / w) * 70)); // 232 → ~162
    }
  }
  // A thick, near-black diagonal stroke (the "signature").
  for (let t = 0; t <= 160; t++) {
    const x = 20 + t;
    const y = 20 + Math.round((t / 160) * 60);
    for (let dx = -2; dx <= 2; dx++) for (let dy = -2; dy <= 2; dy++) set(img, x + dx, y + dy, 22);
  }
  // Isolated single-pixel speckle noise (must be despeckled away).
  for (const [sx, sy] of [
    [60, 8],
    [150, 92],
    [180, 12],
    [30, 88],
  ]) {
    set(img, sx, sy, 28);
  }
  return img;
}

// --- tests ----------------------------------------------------------------

describe('cleanToStandard', () => {
  it('extracts a dark stroke from a gray-gradient noisy sample to dark-on-white at the standard size', () => {
    const out = cleanToStandard(noisyGradientSignature(), 0.5);

    expect(out.width).toBe(SIGNATURE_WIDTH);
    expect(out.height).toBe(SIGNATURE_HEIGHT);

    // Corners (paper) are white — the gradient background and corner speckle never become ink.
    expect(isWhite(out, 0, 0)).toBe(true);
    expect(isWhite(out, SIGNATURE_WIDTH - 1, 0)).toBe(true);
    expect(isWhite(out, 0, SIGNATURE_HEIGHT - 1)).toBe(true);
    expect(isWhite(out, SIGNATURE_WIDTH - 1, SIGNATURE_HEIGHT - 1)).toBe(true);

    // The stroke survived as dark ink — but nowhere near the whole canvas (background rejected).
    const dark = darkPixelCount(out);
    expect(dark).toBeGreaterThan(200);
    expect(dark).toBeLessThan(SIGNATURE_WIDTH * SIGNATURE_HEIGHT * 0.5);
  });

  it('returns an all-white raster for a blank (white) input', () => {
    const out = cleanToStandard(raster(200, 100, 255), 0.5);
    expect(out.width).toBe(SIGNATURE_WIDTH);
    expect(darkPixelCount(out)).toBe(0);
    expect(isWhite(out, SIGNATURE_WIDTH / 2, SIGNATURE_HEIGHT / 2)).toBe(true);
  });
});

describe('normalizeToStandard', () => {
  it('downscales/letterboxes an OVERSIZED image to the standard size, flattened dark-on-white', () => {
    // 1040×340 (double the standard) — mostly white with a dark block in the middle.
    const big = raster(1040, 340, 255);
    for (let y = 120; y < 220; y++) for (let x = 400; x < 640; x++) set(big, x, y, 30);

    const out = normalizeToStandard(big);
    expect(out.width).toBe(SIGNATURE_WIDTH);
    expect(out.height).toBe(SIGNATURE_HEIGHT);
    expect(isWhite(out, 0, 0)).toBe(true); // letterbox paper stays white
    expect(darkPixelCount(out)).toBeGreaterThan(100); // the block came through as ink
  });
});

describe('fitFontPx', () => {
  const linear = (perChar: number, len: number) => (px: number) => px * perChar * len;

  it('keeps the max size when the text already fits', () => {
    expect(fitFontPx(linear(0.5, 4), 500, 48)).toBe(48); // 48*0.5*4 = 96 <= 500
  });

  it('shrinks a long name so its rendered width fits the canvas', () => {
    const measure = linear(0.55, 42); // a long name: 42 chars
    const px = fitFontPx(measure, SIGNATURE_WIDTH - 28, 48, 16);
    expect(px).toBeLessThan(48);
    expect(measure(px)).toBeLessThanOrEqual(SIGNATURE_WIDTH - 28);
  });
});
