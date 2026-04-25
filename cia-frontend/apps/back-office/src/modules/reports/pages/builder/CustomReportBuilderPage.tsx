import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Button, Card, CardContent, PageHeader } from '@cia/ui';
import { cn } from '@cia/ui';
import { apiClient } from '@cia/api-client';
import type { ChartType, DataSource, ReportCategory, ReportDefinition, ReportField, ReportFilter } from '../../types/report.types';
import Step1DataSource from './steps/Step1DataSource';
import Step2FieldsFilters from './steps/Step2FieldsFilters';
import Step3Visualisation from './steps/Step3Visualisation';

const STEPS = ['Data Source', 'Fields & Filters', 'Visualisation'];

interface BuilderState {
  dataSource: DataSource | '';
  fields: ReportField[];
  filters: ReportFilter[];
  name: string;
  category: ReportCategory | '';
  chart: { type: ChartType; xAxis: string; yAxis: string };
}

export default function CustomReportBuilderPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [step, setStep] = useState(0);

  const [state, setState] = useState<BuilderState>({
    dataSource: '',
    fields: [],
    filters: [],
    name: '',
    category: '',
    chart: { type: 'TABLE_ONLY', xAxis: '', yAxis: '' },
  });

  const saveReport = useMutation<ReportDefinition, Error, BuilderState>({
    mutationFn: async (s) => {
      const payload = {
        name: s.name,
        category: s.category,
        dataSource: s.dataSource,
        config: {
          fields: s.fields,
          filters: s.filters,
          chart: s.chart.type !== 'TABLE_ONLY'
            ? { type: s.chart.type, xAxis: s.chart.xAxis, yAxis: s.chart.yAxis }
            : { type: 'TABLE_ONLY' },
        },
      };
      const res = id
        ? await apiClient.put<{ data: ReportDefinition }>(`/api/v1/reports/definitions/${id}`, payload)
        : await apiClient.post<{ data: ReportDefinition }>('/api/v1/reports/definitions', payload);
      return res.data.data;
    },
    onSuccess: (report) => {
      queryClient.invalidateQueries({ queryKey: ['reports'] });
      navigate(`/reports/run/${report.id}`);
    },
  });

  function canAdvance() {
    if (step === 0) return !!state.dataSource;
    if (step === 1) return state.fields.length > 0;
    if (step === 2) return !!state.name && !!state.category;
    return false;
  }

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title={id ? 'Edit Custom Report' : 'New Custom Report'}
        description="Configure your report in three steps, then Save & Run."
      />

      {/* Stepper */}
      <div className="flex items-center gap-0">
        {STEPS.map((label, i) => (
          <div key={i} className="flex items-center">
            <button
              onClick={() => i < step && setStep(i)}
              className={cn(
                'flex items-center gap-2 px-4 py-2 rounded-full text-sm font-medium transition-colors',
                i === step
                  ? 'bg-primary text-primary-foreground'
                  : i < step
                  ? 'bg-primary/15 text-primary cursor-pointer hover:bg-primary/25'
                  : 'bg-secondary text-muted-foreground cursor-default'
              )}
            >
              <span className={cn(
                'inline-flex h-5 w-5 items-center justify-center rounded-full text-xs font-bold',
                i === step ? 'bg-primary-foreground/20' : i < step ? 'bg-primary/30' : 'bg-muted'
              )}>
                {i + 1}
              </span>
              {label}
            </button>
            {i < STEPS.length - 1 && (
              <div className={cn('h-0.5 w-8', i < step ? 'bg-primary' : 'bg-border')} />
            )}
          </div>
        ))}
      </div>

      {/* Step content */}
      <Card>
        <CardContent className="pt-6">
          {step === 0 && (
            <Step1DataSource
              value={state.dataSource}
              onChange={(v) => setState((s) => ({ ...s, dataSource: v }))}
            />
          )}
          {step === 1 && (
            <Step2FieldsFilters
              dataSource={state.dataSource}
              selectedFields={state.fields}
              filters={state.filters}
              onChange={(fields, filters) => setState((s) => ({ ...s, fields, filters }))}
            />
          )}
          {step === 2 && (
            <Step3Visualisation
              fields={state.fields}
              name={state.name}
              category={state.category}
              chart={state.chart}
              onNameChange={(v) => setState((s) => ({ ...s, name: v }))}
              onCategoryChange={(v) => setState((s) => ({ ...s, category: v }))}
              onChartChange={(v) => setState((s) => ({ ...s, chart: v }))}
            />
          )}
        </CardContent>
      </Card>

      {/* Navigation */}
      <div className="flex items-center justify-between">
        <Button variant="outline" onClick={() => step > 0 && setStep(step - 1)} disabled={step === 0}>
          ← Back
        </Button>
        <div className="flex gap-2">
          <Button variant="ghost" onClick={() => navigate('/reports')}>Cancel</Button>
          {step < STEPS.length - 1 ? (
            <Button onClick={() => setStep(step + 1)} disabled={!canAdvance()}>
              Next →
            </Button>
          ) : (
            <Button
              onClick={() => saveReport.mutate(state)}
              disabled={!canAdvance() || saveReport.isPending}
            >
              {saveReport.isPending ? 'Saving…' : 'Save & Run'}
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
