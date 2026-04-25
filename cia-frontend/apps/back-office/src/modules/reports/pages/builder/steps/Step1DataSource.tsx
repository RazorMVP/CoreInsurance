import { Card, CardContent } from '@cia/ui';
import { cn } from '@cia/ui';
import { DATA_SOURCE_OPTIONS } from '../../../types/report.types';
import type { DataSource } from '../../../types/report.types';

interface Props {
  value: DataSource | '';
  onChange: (v: DataSource) => void;
}

const DESCRIPTIONS: Record<DataSource, string> = {
  POLICIES:     'Premium, sum insured, product, class, dates, and policy status.',
  CLAIMS:       'Claim number, reserve, payments, class, and status.',
  FINANCE:      'Debit notes, credit notes, receipts, and payments.',
  REINSURANCE:  'RI allocations, treaties, retained and ceded amounts.',
  CUSTOMERS:    'Customer demographics, type, KYC status, and channel.',
  ENDORSEMENTS: 'Endorsement type, pro-rata premium, and effective dates.',
};

export default function Step1DataSource({ value, onChange }: Props) {
  return (
    <div className="space-y-4">
      <p className="text-sm text-muted-foreground">
        Select the primary data source for your custom report.
      </p>
      <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
        {DATA_SOURCE_OPTIONS.map((opt) => (
          <Card
            key={opt.value}
            className={cn(
              'cursor-pointer transition-all hover:border-primary/50',
              value === opt.value && 'border-primary ring-1 ring-primary bg-primary/5'
            )}
            onClick={() => onChange(opt.value)}
          >
            <CardContent className="p-4 space-y-1">
              <p className="font-medium text-sm">{opt.label}</p>
              <p className="text-xs text-muted-foreground">{DESCRIPTIONS[opt.value]}</p>
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  );
}
