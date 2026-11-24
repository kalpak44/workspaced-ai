const COLOURS = ["#f59e0b", "#10b981", "#3b82f6", "#ec4899", "#8b5cf6", "#f97316"];
const PIECES = 36;

/**
 * A short burst of falling confetti for a moment worth celebrating. Purely decorative: hidden from
 * assistive technology, and not drawn at all for someone who asked for reduced motion.
 */
export function Confetti() {
  return (
    <div aria-hidden className="pointer-events-none fixed inset-0 z-[95] overflow-hidden motion-reduce:hidden">
      {Array.from({ length: PIECES }, (_, i) => (
        <span key={i} className="absolute top-0 block h-3 w-2 rounded-sm opacity-0"
          style={{
            left: `${(i * 37) % 100}%`,
            backgroundColor: COLOURS[i % COLOURS.length],
            animation: `confetti-fall ${1.8 + ((i * 7) % 10) / 10}s ease-in ${((i * 13) % 8) / 10}s forwards`,
          }} />
      ))}
    </div>
  );
}
