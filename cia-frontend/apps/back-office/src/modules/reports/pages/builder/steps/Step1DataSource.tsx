import { Card, CardContent } from '@cia/ui';
import { cn } from '@cia/ui';
import { DATA_SOURCE_OPTIONS } from '../../../types/report.types';
import type { DataSource } from '../../../types/report.types';

interface Props {
  value: DataSource | '';
  onChange: (v: DataSource) => void;
}

const DESCRIPTIONS: Record<DataSource, string> = {
  POLICIES:        'Premium, sum insured, product, class, dates, and policy status.',
  CLAIMS:          'Claim number, reserve, payments, class, and status.',
  FINANCE:         'Debit notes, credit notes, receipts, and payments.',
  REINSURANCE:     'RI allocations, treaties, retained and ceded amounts.',
  CUSTOMERS:       'Customer demographics, type, KYC status, and channel.',
  ENDORSEMENTS:    'Endorsement type, pro-rata premium, and effective dates.',
  // Module 12 — Period-End Closures
  TRIAL_BALANCE:   'Aggregated debit, credit, and net balance per account as of a chosen date.',
  GENERAL_LEDGER:  'Per-line journal entries with COA, class, source module, and narrative.',
  GL_PERIOD_LOCK:  'Soft-close, hard-close, and release events across fiscal periods.',
  PAA_LRC:         'Liability for Remaining Coverage roll-forward per group and period.',
  PAA_GROUPS:      'IFRS 17 §22 contract groups — portfolio, cohort year, and onerousness.',
  IFRS17_MOVEMENT: '§103 LRC and LIC movement-analysis disclosure (V38 view).',
  IFRS9_HOLDINGS:  'Financial assets by classification — AC, FVOCI debt/equity, FVPL.',
  IFRS9_CARRYING:  'Per-holding period roll-forward — interest, fair-value change, ECL.',
  IFRS9_MOVEMENT:  '§B5.5.39 combined investment movement disclosure (V40 view).',
  // Fixed-shape aggregate substrates — excluded from DATA_SOURCE_OPTIONS, so
  // these descriptions never render; present only to keep the Record exhaustive.
  RM_COMMISSION:            'RM commission accrual per relationship manager (aggregate substrate).',
  UNDERWRITING_PERFORMANCE: 'Loss/combined-ratio cross-entity underwriting performance (aggregate substrate).',
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
