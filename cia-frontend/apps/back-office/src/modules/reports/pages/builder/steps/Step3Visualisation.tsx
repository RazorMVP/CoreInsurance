import { Card, CardContent, Input, Label, Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@cia/ui';
import { cn } from '@cia/ui';
import type { ChartType, ReportCategory, ReportField } from '../../../types/report.types';

const CHART_OPTIONS: { value: ChartType; label: string; description: string }[] = [
  { value: 'TABLE_ONLY', label: 'Table Only',  description: 'Data table without chart.' },
  { value: 'BAR',        label: 'Bar Chart',   description: 'Compare values across categories.' },
  { value: 'LINE',       label: 'Line Chart',  description: 'Show trends over time.' },
  { value: 'PIE',        label: 'Pie Chart',   description: 'Show proportional breakdown.' },
];

const CATEGORIES: Array<{ value: ReportCategory; label: string }> = [
  { value: 'UNDERWRITING', label: 'Underwriting' },
  { value: 'CLAIMS',       label: 'Claims' },
  { value: 'FINANCE',      label: 'Finance' },
  { value: 'REINSURANCE',  label: 'Reinsurance' },
  { value: 'CUSTOMER',     label: 'Customer' },
  { value: 'REGULATORY',   label: 'Regulatory' },
];

interface ChartSettings {
  type: ChartType;
  xAxis: string;
  yAxis: string;
}

interface Props {
  fields: ReportField[];
  name: string;
  category: ReportCategory | '';
  chart: ChartSettings;
  onNameChange: (v: string) => void;
  onCategoryChange: (v: ReportCategory) => void;
  onChartChange: (v: ChartSettings) => void;
}

export default function Step3Visualisation({
  fields, name, category, chart,
  onNameChange, onCategoryChange, onChartChange,
}: Props) {
  const nonComputed = fields.filter((f) => !f.computed);

  return (
    <div className="space-y-6">
      {/* Report name + category */}
      <div className="grid grid-cols-2 gap-4">
        <div className="space-y-1.5">
          <Label>Report Name *</Label>
          <Input
            placeholder="e.g. Q4 Claims Summary"
            value={name}
            onChange={(e) => onNameChange(e.target.value)}
          />
        </div>
        <div className="space-y-1.5">
          <Label>Category *</Label>
          <Select value={category} onValueChange={(v) => onCategoryChange(v as ReportCategory)}>
            <SelectTrigger>
              <SelectValue placeholder="Select category" />
            </SelectTrigger>
            <SelectContent>
              {CATEGORIES.map((c) => (
                <SelectItem key={c.value} value={c.value}>{c.label}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>

      {/* Chart type */}
      <div className="space-y-2">
        <Label>Visualisation</Label>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
          {CHART_OPTIONS.map((opt) => (
            <Card
              key={opt.value}
              className={cn(
                'cursor-pointer transition-all hover:border-primary/40',
                chart.type === opt.value && 'border-primary ring-1 ring-primary bg-primary/5'
              )}
              onClick={() => onChartChange({ ...chart, type: opt.value })}
            >
              <CardContent className="p-3 space-y-0.5">
                <p className="text-sm font-medium">{opt.label}</p>
                <p className="text-xs text-muted-foreground">{opt.description}</p>
              </CardContent>
            </Card>
          ))}
        </div>
      </div>

      {/* Axis selectors */}
      {chart.type !== 'TABLE_ONLY' && nonComputed.length > 0 && (
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-1.5">
            <Label>X Axis (Category)</Label>
            <Select value={chart.xAxis} onValueChange={(v) => onChartChange({ ...chart, xAxis: v })}>
              <SelectTrigger>
                <SelectValue placeholder="Select field" />
              </SelectTrigger>
              <SelectContent>
                {nonComputed.map((f) => (
                  <SelectItem key={f.key} value={f.key}>{f.label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-1.5">
            <Label>Y Axis (Value)</Label>
            <Select value={chart.yAxis} onValueChange={(v) => onChartChange({ ...chart, yAxis: v })}>
              <SelectTrigger>
                <SelectValue placeholder="Select field" />
              </SelectTrigger>
              <SelectContent>
                {fields.map((f) => (
                  <SelectItem key={f.key} value={f.key}>{f.label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>
      )}
    </div>
  );
}
