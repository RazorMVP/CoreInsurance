import { useForm } from 'react-hook-form';
import {
  Form, FormField, FormItem, FormLabel, FormControl, FormMessage,
  Input, Button,
} from '@cia/ui';
import type { ReportFilter } from '../../types/report.types';

interface Props {
  filters: ReportFilter[];
  onRun: (values: Record<string, string>) => void;
  loading: boolean;
}

export default function ReportFilterForm({ filters, onRun, loading }: Props) {
  const form = useForm<Record<string, string>>({
    defaultValues: Object.fromEntries(filters.map((f) => [f.key, ''])),
  });

  function onSubmit(values: Record<string, string>) {
    // Strip empty optional filters
    const cleaned = Object.fromEntries(
      Object.entries(values).filter(([, v]) => v !== '')
    );
    onRun(cleaned);
  }

  if (filters.length === 0) {
    return (
      <div className="flex justify-end">
        <Button onClick={() => onRun({})} disabled={loading}>
          {loading ? 'Running…' : 'Run Report'}
        </Button>
      </div>
    );
  }

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
          {filters.map((filter) => (
            <FormField
              key={filter.key}
              control={form.control}
              name={filter.key}
              rules={filter.required ? { required: `${filter.label} is required` } : undefined}
              render={({ field }) => (
                <FormItem>
                  <FormLabel>
                    {filter.label}
                    {filter.required && <span className="ml-0.5 text-destructive">*</span>}
                  </FormLabel>
                  <FormControl>
                    <Input
                      type={filter.type === 'DATE' || filter.type === 'DATE_RANGE' ? 'date' : 'text'}
                      placeholder={filter.label}
                      {...field}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
          ))}
        </div>
        <div className="flex justify-end">
          <Button type="submit" disabled={loading}>
            {loading ? 'Running…' : 'Run Report'}
          </Button>
        </div>
      </form>
    </Form>
  );
}
