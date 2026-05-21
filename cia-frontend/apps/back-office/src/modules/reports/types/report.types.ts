export type ReportCategory =
  | 'UNDERWRITING'
  | 'CLAIMS'
  | 'FINANCE'
  | 'REINSURANCE'
  | 'CUSTOMER'
  | 'REGULATORY'
  | 'CLOSURES';

export type ReportType = 'SYSTEM' | 'CUSTOM';

export type DataSource =
  | 'POLICIES'
  | 'CLAIMS'
  | 'FINANCE'
  | 'REINSURANCE'
  | 'CUSTOMERS'
  | 'ENDORSEMENTS'
  // Module 12 — Period-End Closures
  | 'TRIAL_BALANCE'
  | 'GENERAL_LEDGER'
  | 'GL_PERIOD_LOCK'
  | 'PAA_LRC'
  | 'PAA_GROUPS'
  | 'IFRS17_MOVEMENT'
  | 'IFRS9_HOLDINGS'
  | 'IFRS9_CARRYING'
  | 'IFRS9_MOVEMENT';

export type FieldType = 'STRING' | 'MONEY' | 'PERCENT' | 'DATE' | 'NUMBER' | 'INTEGER';
export type FilterType = 'DATE' | 'DATE_RANGE' | 'SELECT' | 'MULTI_SELECT' | 'TEXT' | 'NUMBER';
export type ChartType = 'BAR' | 'LINE' | 'PIE' | 'TABLE_ONLY';

export interface ReportField {
  key: string;
  label: string;
  type: FieldType;
  computed: boolean;
}

export interface ReportFilter {
  key: string;
  label: string;
  type: FilterType;
  required: boolean;
  /** Optional default value set in the Builder; pre-fills the Viewer's filter input. */
  defaultValue?: string;
}

export interface ReportChart {
  type: ChartType;
  xAxis?: string;
  yAxis?: string;
}

export interface ReportConfig {
  fields: ReportField[];
  filters: ReportFilter[];
  groupBy?: string;
  sortBy?: string;
  sortDir?: 'ASC' | 'DESC';
  chart?: ReportChart;
}

export interface ReportDefinition {
  id: string;
  name: string;
  description?: string;
  category: ReportCategory;
  type: ReportType;
  dataSource: DataSource;
  config: ReportConfig;
  pinnable: boolean;
  active: boolean;
  createdAt: string;
}

export interface ReportResultDto {
  columns: ReportField[];
  rows: Record<string, unknown>[];
  totalRows: number;
}

export interface ReportRunRequest {
  reportId: string;
  filters?: Record<string, string>;
  format?: 'JSON' | 'CSV' | 'PDF';
}

export interface CreateReportRequest {
  name: string;
  description?: string;
  category: ReportCategory;
  dataSource: DataSource;
  config: ReportConfig;
}

export interface ReportAccessPolicy {
  id: string;
  accessGroupId: string;
  category?: ReportCategory;
  report?: ReportDefinition;
  canView: boolean;
  canExportCsv: boolean;
  canExportPdf: boolean;
}

export const CATEGORY_LABELS: Record<ReportCategory, string> = {
  UNDERWRITING: 'Underwriting',
  CLAIMS: 'Claims',
  FINANCE: 'Finance',
  REINSURANCE: 'Reinsurance',
  CUSTOMER: 'Customer',
  REGULATORY: 'Regulatory',
  CLOSURES: 'Closures',
};

export const CATEGORY_COLORS: Record<ReportCategory, string> = {
  UNDERWRITING: 'text-blue-600 bg-blue-50 border-blue-200',
  CLAIMS: 'text-red-600 bg-red-50 border-red-200',
  FINANCE: 'text-emerald-600 bg-emerald-50 border-emerald-200',
  REINSURANCE: 'text-violet-600 bg-violet-50 border-violet-200',
  CUSTOMER: 'text-amber-600 bg-amber-50 border-amber-200',
  REGULATORY: 'text-gray-600 bg-gray-50 border-gray-200',
  CLOSURES: 'text-cyan-700 bg-cyan-50 border-cyan-200',
};

export const DATA_SOURCE_OPTIONS: { value: DataSource; label: string }[] = [
  { value: 'POLICIES',         label: 'Policies' },
  { value: 'CLAIMS',           label: 'Claims' },
  { value: 'FINANCE',          label: 'Finance' },
  { value: 'REINSURANCE',      label: 'Reinsurance' },
  { value: 'CUSTOMERS',        label: 'Customers' },
  { value: 'ENDORSEMENTS',     label: 'Endorsements' },
  // Module 12 — Period-End Closures
  { value: 'TRIAL_BALANCE',    label: 'Trial Balance' },
  { value: 'GENERAL_LEDGER',   label: 'General Ledger' },
  { value: 'GL_PERIOD_LOCK',   label: 'Period Locks' },
  { value: 'PAA_LRC',          label: 'PAA — LRC' },
  { value: 'PAA_GROUPS',       label: 'PAA — Contract Groups' },
  { value: 'IFRS17_MOVEMENT',  label: 'IFRS 17 §103 Movement' },
  { value: 'IFRS9_HOLDINGS',   label: 'IFRS 9 — Holdings' },
  { value: 'IFRS9_CARRYING',   label: 'IFRS 9 — Carrying Value' },
  { value: 'IFRS9_MOVEMENT',   label: 'IFRS 9 §B5.5.39 Movement' },
];
