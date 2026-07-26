import {
  useEffect,
  useId,
  useRef,
  type CSSProperties,
  type HTMLAttributes,
  type ReactNode,
} from "react";
import { registerUiNode } from "./registry.ts";
import type { UiSnapshot, UiSourceDef } from "./types.ts";

type TagName = "div" | "section" | "article" | "li" | "tr" | "span" | "td" | "header" | "footer";

type Props = {
  source: UiSourceDef;
  /** Runtime fields (already filtered — sanitize runs again at copy). */
  snapshot?: UiSnapshot | null | (() => UiSnapshot | null | undefined);
  /** Optional visible text override; else DOM textContent of the root. */
  visibleText?: string | null | (() => string | null | undefined);
  className?: string;
  style?: CSSProperties;
  /** Wrapper element. Default div. */
  as?: TagName;
  children: ReactNode;
} & Omit<HTMLAttributes<HTMLElement>, "className" | "style" | "children">;

/**
 * Marks a visual region as inspectable. Puts data-ui-id / data-ui-instance
 * and registers a live snapshot getter for clipboard export.
 */
export function UiInspectable({
  source,
  snapshot,
  visibleText,
  className,
  style,
  as = "div",
  children,
  ...rest
}: Props) {
  const instanceId = useId();
  const rootRef = useRef<HTMLElement | null>(null);
  const snapRef = useRef(snapshot);
  const visibleRef = useRef(visibleText);
  snapRef.current = snapshot;
  visibleRef.current = visibleText;

  useEffect(() => {
    return registerUiNode({
      instanceId,
      def: source,
      getSnapshot: () => {
        const s = snapRef.current;
        return typeof s === "function" ? s() : s;
      },
      getVisibleText: () => {
        const v = visibleRef.current;
        if (typeof v === "function") return v();
        if (v != null && v !== "") return v;
        return rootRef.current?.innerText?.slice(0, 240) ?? null;
      },
    });
  }, [instanceId, source]);

  const Tag = as;
  return (
    <Tag
      ref={rootRef as never}
      className={className}
      style={style}
      data-ui-id={source.id}
      data-ui-instance={instanceId}
      data-ui-region={source.region}
      {...rest}
    >
      {children}
    </Tag>
  );
}
