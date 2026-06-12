import { Badge } from '@cia/ui';

export default function StatusBadge({ active }: { active: boolean }) {
  return active
    ? <Badge className="bg-primary/15 text-primary">● Active</Badge>
    : <Badge className="bg-amber-500/15 text-amber-400">● Suspended</Badge>;
}
