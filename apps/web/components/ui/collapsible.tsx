'use client';

import * as CollapsiblePrimitive from '@radix-ui/react-collapsible';

/**
 * Collapsible primitive (Radix). The building block for the console's grouped listings — see
 * {@link ../console/grouped-list.tsx}, which is what screens actually consume.
 */
const Collapsible = CollapsiblePrimitive.Root;
const CollapsibleTrigger = CollapsiblePrimitive.CollapsibleTrigger;
const CollapsibleContent = CollapsiblePrimitive.CollapsibleContent;

export { Collapsible, CollapsibleTrigger, CollapsibleContent };
