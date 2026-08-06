'use client';

import * as React from 'react';
import { motion, useReducedMotion, useScroll, useTransform } from 'framer-motion';

/**
 * The lifecycle timeline's connecting line — an indigo fill that tracks scroll progress through the section
 * (the "connecting motion"). Absolutely positioned over the steps container (its ref is the scroll target).
 * Under reduced motion it renders as a fully-filled static line. Decorative only; aria-hidden.
 */
export function LifecycleRail() {
  const ref = React.useRef<HTMLDivElement>(null);
  const reduce = useReducedMotion();
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start 65%', 'end 55%'] });
  const scaleY = useTransform(scrollYProgress, [0, 1], [0, 1]);

  return (
    <div
      ref={ref}
      aria-hidden
      className="pointer-events-none absolute inset-y-0 left-4 w-px md:left-1/2 md:-translate-x-1/2"
    >
      <div className="absolute inset-0 bg-white/10" />
      {reduce ? (
        <div className="absolute inset-0 bg-gradient-to-b from-primary to-primary-bright" />
      ) : (
        <motion.div
          style={{ scaleY, transformOrigin: 'top' }}
          className="absolute inset-0 bg-gradient-to-b from-primary to-primary-bright"
        />
      )}
    </div>
  );
}
