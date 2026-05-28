/**
 * Light display formatting for Nigerian E.164 phone numbers.
 * +2349012345678 → "+234 901 234 5678". Falls through to raw for
 * anything that doesn't match the +234########## shape.
 */
export function formatPhone(raw: string | null | undefined): string {
  if (!raw) return '';
  const trimmed = raw.trim();
  // +234 + 10 digits
  const m = trimmed.match(/^\+234(\d{3})(\d{3})(\d{4})$/);
  if (m) return `+234 ${m[1]} ${m[2]} ${m[3]}`;
  return trimmed;
}
