export function Avatar({ src, large = false }: { readonly src?: string; readonly large?: boolean }) {
  const size = large ? "size-16" : "size-6";
  if (src) {
    return <img src={src} alt="Your avatar" className={`${size} shrink-0 rounded-full object-cover`} />;
  }
  return (
    <span aria-label="Default avatar" role="img" className={`${size} shrink-0 rounded-full bg-linear-to-br from-sky-400 via-fuchsia-500 to-orange-400`} />
  );
}
