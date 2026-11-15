import {
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
} from 'react';

export interface DropdownOption {
  value: string;
  label: string;
}

interface Props {
  value: string;
  options: DropdownOption[];
  onChange: (value: string) => void;
  label?: string;
  ariaLabel?: string;
  /** The id of a hint shown next to the dropdown. */
  describedBy?: string;
  width?: number | string;
}

interface Position {
  top: number;
  left: number;
  width: number;
}

const GAP = 4;
const VIEWPORT_MARGIN = 8;

/**
 * A small anchored listbox. The menu is positioned with fixed coordinates from the trigger,
 * flipped above it when there is no room below, and clamped to the viewport. Scrolling inside
 * the menu keeps it open; scrolling the page moves it.
 *
 * <p>CSS anchor positioning would do the flip declaratively, but the JavaFX WebView used by
 * the desktop app does not support it, so this is measured in JavaScript.
 */
export default function Dropdown({
  value,
  options,
  onChange,
  label,
  ariaLabel,
  describedBy,
  width,
}: Props) {
  const labelId = useId();
  const valueId = useId();
  const [open, setOpen] = useState(false);
  const [position, setPosition] = useState<Position | null>(null);
  const [activeIndex, setActiveIndex] = useState(0);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const itemRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const search = useRef({ query: '', timer: 0 });

  const selectedIndex = options.findIndex((option) => option.value === value);

  const place = useCallback(() => {
    const trigger = triggerRef.current;
    if (!trigger) return;
    const bounds = trigger.getBoundingClientRect();
    const height = menuRef.current?.offsetHeight ?? 0;
    const below = bounds.bottom + GAP;
    const above = bounds.top - GAP - height;
    const flip =
      height > 0 && below + height > window.innerHeight - VIEWPORT_MARGIN && above >= VIEWPORT_MARGIN;
    const top = flip
      ? above
      : Math.min(below, Math.max(VIEWPORT_MARGIN, window.innerHeight - height - VIEWPORT_MARGIN));
    const left = Math.min(
      Math.max(VIEWPORT_MARGIN, bounds.left),
      Math.max(VIEWPORT_MARGIN, window.innerWidth - bounds.width - VIEWPORT_MARGIN),
    );
    setPosition({ top, left, width: bounds.width });
  }, []);

  const openMenu = useCallback(() => {
    setActiveIndex(selectedIndex >= 0 ? selectedIndex : 0);
    // Place from the trigger first so the menu has coordinates on its first render; the
    // layout effect then re-measures it and flips it above when there is no room below.
    place();
    setOpen(true);
  }, [selectedIndex, place]);

  const closeMenu = useCallback((returnFocus: boolean) => {
    setOpen(false);
    setPosition(null);
    if (returnFocus) triggerRef.current?.focus();
  }, []);

  useLayoutEffect(() => {
    if (open) place();
  }, [open, place, options.length]);

  // Move real focus with the active option, which is what a listbox keyboard contract means.
  useLayoutEffect(() => {
    if (open) itemRefs.current[activeIndex]?.focus();
  }, [open, activeIndex]);

  useEffect(() => {
    if (!open) return;

    const onPointerDown = (event: MouseEvent) => {
      const target = event.target as Node;
      if (triggerRef.current?.contains(target) || menuRef.current?.contains(target)) return;
      closeMenu(false);
    };
    // A scroll inside the menu is the user reading the list, not a reason to close it.
    const onScroll = (event: Event) => {
      if (menuRef.current && event.target instanceof Node && menuRef.current.contains(event.target))
        return;
      place();
    };
    const onResize = () => place();

    document.addEventListener('mousedown', onPointerDown);
    window.addEventListener('scroll', onScroll, true);
    window.addEventListener('resize', onResize);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      window.removeEventListener('scroll', onScroll, true);
      window.removeEventListener('resize', onResize);
    };
  }, [open, place, closeMenu]);

  const choose = (index: number) => {
    const option = options[index];
    if (!option) return;
    onChange(option.value);
    closeMenu(true);
  };

  const move = (from: number, delta: number) =>
    options.length === 0 ? 0 : (from + delta + options.length) % options.length;

  function typeahead(key: string) {
    const query = (search.current.query + key).toLowerCase();
    search.current.query = query;
    window.clearTimeout(search.current.timer);
    search.current.timer = window.setTimeout(() => {
      search.current.query = '';
    }, 600);
    for (let offset = 1; offset <= options.length; offset++) {
      const index = (activeIndex + offset) % options.length;
      if (options[index].label.toLowerCase().startsWith(query)) {
        setActiveIndex(index);
        return;
      }
    }
  }

  const onMenuKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        setActiveIndex((index) => move(index, 1));
        break;
      case 'ArrowUp':
        event.preventDefault();
        setActiveIndex((index) => move(index, -1));
        break;
      case 'Home':
        event.preventDefault();
        setActiveIndex(0);
        break;
      case 'End':
        event.preventDefault();
        setActiveIndex(Math.max(options.length - 1, 0));
        break;
      case 'Escape':
        event.preventDefault();
        closeMenu(true);
        break;
      case 'Tab':
        closeMenu(false);
        break;
      case 'Enter':
      case ' ':
        event.preventDefault();
        choose(activeIndex);
        break;
      default:
        if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey)
          typeahead(event.key);
    }
  };

  const onTriggerKeyDown = (event: ReactKeyboardEvent<HTMLButtonElement>) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      openMenu();
    }
  };

  const selected = options.find((option) => option.value === value);
  const shown = selected?.label ?? (value || 'Select…');

  // The name of the button is its label and the value it shows: a name that leaves the
  // visible text out hides the current choice from a screen reader and from voice control.
  const name = label
    ? { 'aria-labelledby': `${labelId} ${valueId}` }
    : { 'aria-label': ariaLabel ? `${ariaLabel}: ${shown}` : shown };
  const menuName = label ? { 'aria-labelledby': labelId } : { 'aria-label': ariaLabel };

  return (
    <div className="dropdown" style={{ width }}>
      {label && (
        <span className="setting-label" id={labelId}>
          {label}
        </span>
      )}
      <button
        ref={triggerRef}
        type="button"
        className="dropdown-trigger"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-describedby={describedBy}
        {...name}
        onClick={() => (open ? closeMenu(false) : openMenu())}
        onKeyDown={onTriggerKeyDown}
      >
        <span className="dropdown-value" id={valueId}>
          {shown}
        </span>
        <span className="dropdown-chevron" aria-hidden="true">
          ▾
        </span>
      </button>
      {open && position && (
        <div
          ref={menuRef}
          className="dropdown-menu"
          role="listbox"
          {...menuName}
          style={{
            position: 'fixed',
            top: position.top,
            left: position.left,
            width: position.width,
          }}
          onKeyDown={onMenuKeyDown}
        >
          {options.map((option, index) => (
            <button
              key={option.value}
              ref={(node) => {
                itemRefs.current[index] = node;
              }}
              type="button"
              role="option"
              tabIndex={-1}
              aria-selected={option.value === value}
              className={`dropdown-item ${option.value === value ? 'selected' : ''}`}
              onClick={() => choose(index)}
              onMouseEnter={() => setActiveIndex(index)}
            >
              {option.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
