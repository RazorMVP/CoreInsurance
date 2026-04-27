// Shared clause data for quote sheets and PDF preview.
// In production: fetched from GET /api/v1/setup/clauses
export interface QuoteClause {
  id:    string;
  title: string;
  text:  string;
}

export const INITIAL_CLAUSES: QuoteClause[] = [
  { id: 'c1', title: 'Third Party Liability',          text: 'Indemnity for third party bodily injury and property damage as per the Motor Vehicles (Third Party Insurance) Act.' },
  { id: 'c2', title: 'Own Damage',                     text: 'Covers accidental damage to the insured vehicle including fire, theft and malicious damage.' },
  { id: 'c3', title: 'Exclusion — Racing',             text: 'This policy does not cover loss or damage arising from or whilst the vehicle is used in racing, rallying or similar events.' },
  { id: 'c4', title: 'Special Condition — Alarm System', text: 'It is a special condition that a NSIA-approved burglar alarm system is installed and in full operation throughout the period of insurance.' },
  { id: 'c5', title: 'Burglary & Housebreaking',       text: 'Indemnity against loss or damage resulting from burglary, housebreaking or theft involving forcible entry.' },
  { id: 'c6', title: 'Exclusion — Wear & Tear',        text: 'This policy excludes damage attributable to gradual deterioration, wear and tear or inherent vice.' },
  { id: 'c7', title: 'Marine — Institute Cargo',       text: 'Coverage in accordance with the Institute Cargo Clauses (A) for all risks of physical loss or damage.' },
  { id: 'c8', title: 'Warranty — Security Survey',     text: 'It is warranted that a security survey be completed and recommendations implemented within 30 days of policy inception.' },
];
