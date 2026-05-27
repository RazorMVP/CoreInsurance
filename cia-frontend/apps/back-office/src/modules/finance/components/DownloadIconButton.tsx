import { Button } from '@cia/ui';
import { HugeiconsIcon } from '@hugeicons/react';
import { Download01Icon } from '@hugeicons/core-free-icons';
import { useDownloadReceiptPdf } from '../hooks/useReceipts';
import { useDownloadPaymentPdf } from '../hooks/usePayments';
import type { PdfDocumentType } from '@cia/api-client';

interface Props {
  type:      PdfDocumentType;
  id:        string;
  parentId:  string;        // dnId for RECEIPT, cnId for PAYMENT
  reference: string;        // for the filename
  pdfPath:   string | null;
}

/**
 * Small inline icon button that downloads the row's PDF. Disabled when
 * pdfPath is null (PDF was never generated). The backend writes a
 * pdf_download_log row server-side; the frontend doesn't need to push
 * anywhere — the RecentDownloadsPanel's useQuery picks it up.
 */
export default function DownloadIconButton({ type, id, parentId, reference, pdfPath }: Props) {
  const downloadReceipt = useDownloadReceiptPdf();
  const downloadPayment = useDownloadPaymentPdf();
  const mutation = type === 'RECEIPT' ? downloadReceipt : downloadPayment;
  const disabled = pdfPath === null || mutation.isPending;

  const onClick = () => {
    if (type === 'RECEIPT') {
      downloadReceipt.mutate({ dnId: parentId, receiptId: id, reference });
    } else {
      downloadPayment.mutate({ cnId: parentId, paymentId: id, reference });
    }
  };

  return (
    <Button
      variant="ghost"
      size="icon"
      onClick={onClick}
      disabled={disabled}
      title={pdfPath === null ? 'PDF unavailable' : 'Download PDF'}
    >
      <HugeiconsIcon icon={Download01Icon} size={16} />
    </Button>
  );
}
