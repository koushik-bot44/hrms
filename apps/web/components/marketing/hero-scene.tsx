'use client';

import * as React from 'react';
import { HeroSceneFallback } from '@/components/marketing/hero-scene-fallback';

/**
 * The hero's one interactive scene — a lightweight Canvas-2D constellation that evolves the AuthIllustration
 * language: nodes drift slowly, thin links appear between near neighbours, and the whole field parallaxes a
 * touch toward the pointer. NO dependency (no three.js), client-only, and loaded via next/dynamic(ssr:false)
 * so it never ships in the page's first-load JS and never blocks LCP. Under prefers-reduced-motion (or before
 * mount) it renders the static SVG fallback instead. All colour is read from the `.marketing` --primary-bright
 * token so it stays on-brand; nothing here touches the network or any app state.
 */

interface Node {
  x: number;
  y: number;
  vx: number;
  vy: number;
  r: number;
}

function readAccent(): string {
  if (typeof window === 'undefined') return '239 90% 74%';
  const v = getComputedStyle(document.documentElement).getPropertyValue('--primary-bright').trim();
  return v || '239 90% 74%';
}

export function HeroScene({ className }: { className?: string }) {
  const canvasRef = React.useRef<HTMLCanvasElement | null>(null);
  const [animate, setAnimate] = React.useState(false);

  React.useEffect(() => {
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
    if (mq.matches) return; // keep the static fallback
    setAnimate(true);
  }, []);

  React.useEffect(() => {
    if (!animate) return;
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const accent = readAccent();
    let width = 0;
    let height = 0;
    let dpr = Math.min(window.devicePixelRatio || 1, 2);
    let nodes: Node[] = [];
    const pointer = { x: 0.5, y: 0.5, active: false };
    let raf = 0;
    let running = true;

    const resize = () => {
      const rect = canvas.getBoundingClientRect();
      width = rect.width;
      height = rect.height;
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      canvas.width = Math.round(width * dpr);
      canvas.height = Math.round(height * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      // Scale node count to area but cap it hard for perf.
      const target = Math.max(26, Math.min(62, Math.round((width * height) / 9000)));
      nodes = Array.from({ length: target }, () => ({
        x: Math.random() * width,
        y: Math.random() * height,
        vx: (Math.random() - 0.5) * 0.22,
        vy: (Math.random() - 0.5) * 0.22,
        r: Math.random() * 1.6 + 0.8,
      }));
    };

    const LINK = 118; // px distance under which two nodes link
    const draw = () => {
      if (!running) return;
      ctx.clearRect(0, 0, width, height);
      const ox = (pointer.x - 0.5) * 26;
      const oy = (pointer.y - 0.5) * 26;

      for (const n of nodes) {
        n.x += n.vx;
        n.y += n.vy;
        if (n.x < -20) n.x = width + 20;
        if (n.x > width + 20) n.x = -20;
        if (n.y < -20) n.y = height + 20;
        if (n.y > height + 20) n.y = -20;
      }

      // Links.
      for (let i = 0; i < nodes.length; i++) {
        for (let j = i + 1; j < nodes.length; j++) {
          const a = nodes[i];
          const b = nodes[j];
          const dx = a.x - b.x;
          const dy = a.y - b.y;
          const d = Math.hypot(dx, dy);
          if (d < LINK) {
            const alpha = (1 - d / LINK) * 0.5;
            ctx.strokeStyle = `hsl(${accent} / ${alpha.toFixed(3)})`;
            ctx.lineWidth = 1;
            ctx.beginPath();
            ctx.moveTo(a.x + ox, a.y + oy);
            ctx.lineTo(b.x + ox, b.y + oy);
            ctx.stroke();
          }
        }
      }

      // Nodes.
      for (const n of nodes) {
        ctx.fillStyle = `hsl(${accent} / 0.85)`;
        ctx.beginPath();
        ctx.arc(n.x + ox, n.y + oy, n.r, 0, Math.PI * 2);
        ctx.fill();
        // Soft halo on the larger nodes.
        if (n.r > 1.8) {
          ctx.fillStyle = `hsl(${accent} / 0.10)`;
          ctx.beginPath();
          ctx.arc(n.x + ox, n.y + oy, n.r * 4, 0, Math.PI * 2);
          ctx.fill();
        }
      }

      raf = requestAnimationFrame(draw);
    };

    const onPointer = (e: PointerEvent) => {
      const rect = canvas.getBoundingClientRect();
      pointer.x = (e.clientX - rect.left) / rect.width;
      pointer.y = (e.clientY - rect.top) / rect.height;
    };
    const onVisibility = () => {
      if (document.hidden) {
        running = false;
        cancelAnimationFrame(raf);
      } else if (!running) {
        running = true;
        raf = requestAnimationFrame(draw);
      }
    };

    resize();
    draw();
    window.addEventListener('resize', resize);
    window.addEventListener('pointermove', onPointer);
    document.addEventListener('visibilitychange', onVisibility);

    return () => {
      running = false;
      cancelAnimationFrame(raf);
      window.removeEventListener('resize', resize);
      window.removeEventListener('pointermove', onPointer);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [animate]);

  if (!animate) return <HeroSceneFallback className={className} />;
  return <canvas ref={canvasRef} aria-hidden className={className} />;
}

export default HeroScene;
