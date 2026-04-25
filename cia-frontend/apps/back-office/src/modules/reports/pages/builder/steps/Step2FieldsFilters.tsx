import { Badge, Checkbox, Label } from '@cia/ui';
import type { ReportField, ReportFilter } from '../../../types/report.types';

const AVAILABLE_FIELDS: Record<string, ReportField[]> = {
  POLICIES: [
    { key: 'policy_number',     label: 'Policy Number',    type: 'STRING',  computed: false },
    { key: 'customer_name',     label: 'Customer',         type: 'STRING',  computed: false },
    { key: 'class_of_business', label: 'Class',            type: 'STRING',  computed: false },
    { key: 'product_name',      label: 'Product',          type: 'STRING',  computed: false },
    { key: 'sum_insured',       label: 'Sum Insured (₦)',  type: 'MONEY',   computed: false },
    { key: 'premium',           label: 'Premium (₦)',      type: 'MONEY',   computed: false },
    { key: 'status',            label: 'Status',           type: 'STRING',  computed: false },
    { key: 'start_date',        label: 'Start Date',       type: 'DATE',    computed: false },
    { key: 'end_date',          label: 'Expiry Date',      type: 'DATE',    computed: false },
    { key: 'loss_ratio',        label: 'Loss Ratio %',     type: 'PERCENT', computed: true  },
    { key: 'conversion_pct',    label: 'Conversion %',     type: 'PERCENT', computed: true  },
  ],
  CLAIMS: [
    { key: 'claim_number',      label: 'Claim Number',     type: 'STRING',  computed: false },
    { key: 'policy_number',     label: 'Policy',           type: 'STRING',  computed: false },
    { key: 'customer_name',     label: 'Insured',          type: 'STRING',  computed: false },
    { key: 'class_of_business', label: 'Class',            type: 'STRING',  computed: false },
    { key: 'reserve_amount',    label: 'Reserve (₦)',      type: 'MONEY',   computed: false },
    { key: 'total_paid',        label: 'Paid (₦)',         type: 'MONEY',   computed: false },
    { key: 'status',            label: 'Status',           type: 'STRING',  computed: false },
    { key: 'registered_at',     label: 'Registered',       type: 'DATE',    computed: false },
    { key: 'loss_ratio',        label: 'Loss Ratio %',     type: 'PERCENT', computed: true  },
  ],
  FINANCE: [
    { key: 'debit_note_number', label: 'Reference',        type: 'STRING',  computed: false },
    { key: 'policy_number',     label: 'Policy',           type: 'STRING',  computed: false },
    { key: 'customer_name',     label: 'Customer',         type: 'STRING',  computed: false },
    { key: 'amount',            label: 'Amount (₦)',       type: 'MONEY',   computed: false },
    { key: 'status',            label: 'Status',           type: 'STRING',  computed: false },
    { key: 'due_date',          label: 'Due Date',         type: 'DATE',    computed: false },
  ],
  REINSURANCE: [
    { key: 'policy_number',     label: 'Policy',           type: 'STRING',  computed: false },
    { key: 'treaty_name',       label: 'Treaty',           type: 'STRING',  computed: false },
    { key: 'treaty_type',       label: 'Treaty Type',      type: 'STRING',  computed: false },
    { key: 'retained_amount',   label: 'Retained (₦)',     type: 'MONEY',   computed: false },
    { key: 'ceded_amount',      label: 'Ceded (₦)',        type: 'MONEY',   computed: false },
    { key: 'status',            label: 'Status',           type: 'STRING',  computed: false },
    { key: 'utilisation_pct',   label: 'Utilisation %',    type: 'PERCENT', computed: true  },
    { key: 'cession_pct',       label: 'Cession %',        type: 'PERCENT', computed: true  },
  ],
  CUSTOMERS: [
    { key: 'full_name',         label: 'Customer',         type: 'STRING',  computed: false },
    { key: 'customer_type',     label: 'Type',             type: 'STRING',  computed: false },
    { key: 'channel',           label: 'Channel',          type: 'STRING',  computed: false },
    { key: 'kyc_status',        label: 'KYC Status',       type: 'STRING',  computed: false },
    { key: 'created_at',        label: 'Onboarded',        type: 'DATE',    computed: false },
  ],
  ENDORSEMENTS: [
    { key: 'endorsement_number', label: 'Endorsement No.', type: 'STRING',  computed: false },
    { key: 'policy_number',      label: 'Policy',          type: 'STRING',  computed: false },
    { key: 'customer_name',      label: 'Insured',         type: 'STRING',  computed: false },
    { key: 'endorsement_type',   label: 'Type',            type: 'STRING',  computed: false },
    { key: 'endorsement_premium',label: 'Premium (₦)',     type: 'MONEY',   computed: false },
    { key: 'effective_date',     label: 'Effective Date',  type: 'DATE',    computed: false },
    { key: 'status',             label: 'Status',          type: 'STRING',  computed: false },
  ],
};

interface Props {
  dataSource: string;
  selectedFields: ReportField[];
  filters: ReportFilter[];
  onChange: (fields: ReportField[], filters: ReportFilter[]) => void;
}

export default function Step2FieldsFilters({ dataSource, selectedFields, filters, onChange }: Props) {
  const available = AVAILABLE_FIELDS[dataSource] ?? [];

  function toggleField(field: ReportField) {
    const exists = selectedFields.some((f) => f.key === field.key);
    const updated = exists
      ? selectedFields.filter((f) => f.key !== field.key)
      : [...selectedFields, field];
    onChange(updated, filters);
  }

  function toggleFilter(key: string, label: string) {
    const exists = filters.some((f) => f.key === key);
    const updated = exists
      ? filters.filter((f) => f.key !== key)
      : [...filters, { key, label, type: 'DATE' as const, required: false }];
    onChange(selectedFields, updated);
  }

  return (
    <div className="space-y-5">
      <div className="space-y-2">
        <p className="text-sm font-medium">Select columns to display</p>
        <div className="grid grid-cols-2 md:grid-cols-3 gap-2">
          {available.map((field) => {
            const checked = selectedFields.some((f) => f.key === field.key);
            return (
              <div key={field.key} className="flex items-center gap-2 p-2 rounded border hover:bg-secondary/50 transition-colors">
                <Checkbox
                  id={`field-${field.key}`}
                  checked={checked}
                  onCheckedChange={() => toggleField(field)}
                />
                <Label htmlFor={`field-${field.key}`} className="cursor-pointer text-sm leading-none">
                  {field.label}
                  {field.computed && (
                    <span className="ml-1.5 text-[9px] font-medium text-muted-foreground border rounded px-1 py-0.5">computed</span>
                  )}
                </Label>
              </div>
            );
          })}
        </div>
      </div>

      {selectedFields.length > 0 && (
        <div className="space-y-1">
          <p className="text-sm font-medium">Column order</p>
          <div className="flex flex-wrap gap-1.5">
            {selectedFields.map((f) => (
              <Badge key={f.key} variant="outline" className="text-xs gap-1">
                {f.label}
                <button onClick={() => toggleField(f)} className="ml-0.5 hover:text-destructive">×</button>
              </Badge>
            ))}
          </div>
        </div>
      )}

      <div className="space-y-2">
        <p className="text-sm font-medium">Date filters</p>
        <div className="flex gap-4">
          {[
            { key: 'date_from', label: 'Date From' },
            { key: 'date_to',   label: 'Date To'   },
          ].map(({ key, label }) => (
            <div key={key} className="flex items-center gap-2">
              <Checkbox
                id={key}
                checked={filters.some((f) => f.key === key)}
                onCheckedChange={() => toggleFilter(key, label)}
              />
              <Label htmlFor={key} className="cursor-pointer text-sm">{label}</Label>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
