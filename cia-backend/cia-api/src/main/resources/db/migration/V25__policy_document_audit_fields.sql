-- Policy document send/acknowledge audit fields (B4.2).
--
-- Adds four columns to the policies table to record when the policy
-- document was dispatched to the insured and when the insured
-- acknowledged receipt. The PDF itself continues to live at
-- policy_document_path; these fields track the delivery + acknowledgement
-- lifecycle that the frontend's "Send to Insured" / "Acknowledge Receipt"
-- buttons need.

ALTER TABLE policies
    ADD COLUMN document_sent_at         TIMESTAMP WITH TIME ZONE,
    ADD COLUMN document_sent_by         VARCHAR(100),
    ADD COLUMN document_acknowledged_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN document_acknowledged_by VARCHAR(100);
