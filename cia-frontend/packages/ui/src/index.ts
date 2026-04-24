// ── Primitives ───────────────────────────────────────────────────────────
export { Button, buttonVariants }    from './components/button';
export type { ButtonProps }          from './components/button';
export { Badge, badgeVariants }      from './components/badge';
export type { BadgeProps }           from './components/badge';
export { Input }                     from './components/input';
export type { InputProps }           from './components/input';
export { Label }                     from './components/label';
export { Textarea }                  from './components/textarea';
export type { TextareaProps }        from './components/textarea';
export { Checkbox }                  from './components/checkbox';
export { Switch }                    from './components/switch';
export { Separator }                 from './components/separator';
export { Skeleton }                  from './components/skeleton';

// ── Select ───────────────────────────────────────────────────────────────
export {
  Select, SelectContent, SelectGroup, SelectItem, SelectLabel,
  SelectScrollDownButton, SelectScrollUpButton, SelectSeparator,
  SelectTrigger, SelectValue,
} from './components/select';

// ── Tabs ─────────────────────────────────────────────────────────────────
export { Tabs, TabsList, TabsTrigger, TabsContent } from './components/tabs';

// ── Dialog ───────────────────────────────────────────────────────────────
export {
  Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter,
  DialogHeader, DialogOverlay, DialogPortal, DialogTitle, DialogTrigger,
} from './components/dialog';

// ── Sheet ────────────────────────────────────────────────────────────────
export {
  Sheet, SheetClose, SheetContent, SheetDescription, SheetFooter,
  SheetHeader, SheetPortal, SheetTitle, SheetTrigger,
} from './components/sheet';

// ── Toast ────────────────────────────────────────────────────────────────
export {
  Toast, ToastAction, ToastClose, ToastDescription,
  ToastProvider, ToastTitle, ToastViewport,
} from './components/toast';
export type { ToastProps, ToastActionElement } from './components/toast';
export { Toaster } from './components/toaster';

// ── Dropdown ─────────────────────────────────────────────────────────────
export {
  DropdownMenu, DropdownMenuCheckboxItem, DropdownMenuContent,
  DropdownMenuGroup, DropdownMenuItem, DropdownMenuLabel,
  DropdownMenuPortal, DropdownMenuRadioGroup, DropdownMenuRadioItem,
  DropdownMenuSeparator, DropdownMenuShortcut, DropdownMenuSub,
  DropdownMenuSubContent, DropdownMenuSubTrigger, DropdownMenuTrigger,
} from './components/dropdown-menu';

// ── Avatar ───────────────────────────────────────────────────────────────
export { Avatar, AvatarFallback, AvatarImage } from './components/avatar';

// ── Card ─────────────────────────────────────────────────────────────────
export {
  Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle,
} from './components/card';

// ── Tooltip ──────────────────────────────────────────────────────────────
export { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from './components/tooltip';

// ── Scroll Area ──────────────────────────────────────────────────────────
export { ScrollArea, ScrollBar } from './components/scroll-area';

// ── Data Table ───────────────────────────────────────────────────────────
export { DataTable }              from './components/data-table/data-table';
export { DataTableColumnHeader }  from './components/data-table/data-table-column-header';
export { DataTablePagination }    from './components/data-table/data-table-pagination';
export { DataTableRowActions }    from './components/data-table/data-table-row-actions';
export { DataTableToolbar }       from './components/data-table/data-table-toolbar';
export type { RowAction }         from './components/data-table/data-table-row-actions';
export type { DataTableToolbarProps } from './components/data-table/data-table-toolbar';

// ── Layout ───────────────────────────────────────────────────────────────
export { PageHeader }  from './components/layout/page-header';
export { PageSection } from './components/layout/page-section';
export { EmptyState }  from './components/layout/empty-state';
export { StatCard }    from './components/layout/stat-card';
export { Breadcrumb }  from './components/layout/breadcrumb';

// ── Form ─────────────────────────────────────────────────────────────────
export {
  Form, FormControl, FormDescription, FormField,
  FormItem, FormLabel, FormMessage, FormRow, FormSection,
  useFormField,
} from './components/form';

// ── Hooks ────────────────────────────────────────────────────────────────
export { useToast, toast } from './hooks/use-toast';

// ── Utility ──────────────────────────────────────────────────────────────
export { cn } from './lib/utils';
