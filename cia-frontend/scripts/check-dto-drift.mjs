#!/usr/bin/env node
/**
 * CI guard: enforce that every `*Dto` interface in
 * `cia-frontend/packages/api-client/src/modules/` matches the field set of its
 * counterpart `*Response.java` in `cia-backend/`.
 *
 * Why this exists: Sessions 78 (BrokerDto), 84a (ProductDto), and 91 (QuoteDto)
 * each surfaced silent drift between frontend Dto types and backend Response
 * DTOs. Jackson silently dropped fields the frontend declared but the backend
 * didn't have; conversely, the frontend missed fields the backend was already
 * serialising. The pattern repeated three times — rule-of-three says automate.
 *
 * How: parse Lombok `@Data class XYZResponse` field declarations from Java
 * source, parse `export interface XYZDto` from the TS api-client, compare key
 * sets, flag both directions of drift.
 *
 * Allow-list: `cia-frontend/scripts/dto-drift.config.json` carries (1) manual
 * mapping overrides where the naming convention doesn't hold, and (2) per-Dto
 * allow-lists for intentional asymmetries. Adding to the allow-list documents
 * the intentional drift in `git blame` so reviewers can question new additions.
 *
 * Run locally: `node cia-frontend/scripts/check-dto-drift.mjs` from repo root.
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(__dirname, '..', '..');
const FRONTEND_MODULES_DIR = join(REPO_ROOT, 'cia-frontend', 'packages', 'api-client', 'src', 'modules');
const BACKEND_DIR = join(REPO_ROOT, 'cia-backend');
const CONFIG_PATH = join(__dirname, 'dto-drift.config.json');

// ── Config ───────────────────────────────────────────────────────────────────
const config = JSON.parse(readFileSync(CONFIG_PATH, 'utf8'));
const manualMap = new Map(Object.entries(config.manualMap ?? {}));
const ignoreDtos = new Set(config.ignoreDtos ?? []);
const allowList = config.allowList ?? {};

// ── Walk all .java files under cia-backend ───────────────────────────────────
function walk(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    let st;
    try { st = statSync(path); } catch { continue; }
    if (st.isDirectory()) {
      if (entry === 'target' || entry === 'build' || entry === 'node_modules') continue;
      walk(path, out);
    } else if (entry.endsWith('.java')) {
      out.push(path);
    }
  }
  return out;
}

const javaFiles = walk(BACKEND_DIR);

// Map of simple class name → absolute file path (first occurrence wins; the
// repo doesn't ship duplicates of Response classes).
const javaByName = new Map();
for (const f of javaFiles) {
  const base = f.split('/').pop().replace(/\.java$/, '');
  if (!javaByName.has(base)) javaByName.set(base, f);
}

// ── Java field extractor ─────────────────────────────────────────────────────
// Matches `private TYPE name;` declarations inside a Lombok-style @Data class.
// TYPE can include generics + commas + nested angles + spaces, e.g.
//   `private Map<String, Object> riskDetails;`
//   `private List<PolicyRiskResponse> risks;`
// We avoid trying to parse the type — only the trailing identifier matters.
//
// Skips:
//   - lines starting with `//` (line comments)
//   - lines starting with `/*` or `*` (block-comment bodies)
//   - lines starting with `@` (annotation lines that don't end in `;`)
//   - static / final fields (rare, but skip for safety)
function extractJavaFields(filePath) {
  const src = readFileSync(filePath, 'utf8');
  const fields = new Set();

  // Strip block comments before scanning so multi-line `/* ... */`
  // documentation comments don't get parsed as fields.
  const stripped = src.replace(/\/\*[\s\S]*?\*\//g, '');

  // ── Java record support ───────────────────────────────────────────────────
  // Records declare their fields in the parenthesised header:
  //   public record EndorsementResponse(
  //       UUID id,
  //       String policyNumber,
  //       ...
  //   ) {}
  // Extract the field names from inside the parens. Each component is
  // `Type name` (possibly with generics + line comments) separated by commas.
  const recordMatch = stripped.match(/\brecord\s+\w+\s*\(([\s\S]*?)\)\s*\{/);
  if (recordMatch) {
    // Split components on commas at the top level (won't see commas inside
    // generic types because Java forbids them in record component declarations
    // — generics on record components have to use Map / nested generics that
    // do contain commas, so we split more carefully).
    let depth = 0;
    let buf = '';
    const components = [];
    for (const ch of recordMatch[1]) {
      if (ch === '<') depth++;
      else if (ch === '>') depth--;
      else if (ch === ',' && depth === 0) {
        components.push(buf);
        buf = '';
        continue;
      }
      buf += ch;
    }
    if (buf.trim()) components.push(buf);
    for (const c of components) {
      // Strip line comments inside the component.
      const cleaned = c.replace(/\/\/.*$/gm, '').trim();
      if (!cleaned) continue;
      // Last identifier in the component is the field name.
      const m = cleaned.match(/(\w+)\s*$/);
      if (m) fields.add(m[1]);
    }
    return fields;
  }

  // ── Lombok @Value class support ───────────────────────────────────────────
  // @Value makes every field private+final but the field declaration in
  // source omits the `private` keyword. Detect @Value on the file and parse
  // the class body lines with a permissive `TYPE name;` pattern. Limited to
  // the class body so we don't pick up `import` / `package` declarations.
  if (/@Value\b/.test(stripped)) {
    const classBody = stripped.match(/\bclass\s+\w+\s*\{([\s\S]*?)\n\}/);
    if (classBody) {
      for (const rawLine of classBody[1].split('\n')) {
        const line = rawLine.trim();
        if (!line || line.startsWith('//') || line.startsWith('@')) continue;
        if (line.startsWith('*')) continue;
        // Skip method signatures + control-flow lines (anything with
        // unbalanced parens/braces ahead of the identifier).
        if (/[(){]/.test(line.replace(/^[^=]*=[^=].*$/, ''))) continue;
        const m = line.match(/^(?:public\s+|protected\s+|private\s+)?(?:static\s+)?(?:final\s+)?[\w<>?,.\s]+?\s+(\w+)\s*(?:=\s*[^;]+)?;\s*$/);
        if (m) fields.add(m[1]);
      }
      return fields;
    }
  }

  // ── Lombok @Data / @Builder class support ─────────────────────────────────
  for (const rawLine of stripped.split('\n')) {
    const line = rawLine.trim();
    if (!line || line.startsWith('//') || line.startsWith('@')) continue;
    if (line.startsWith('*')) continue; // leftover Javadoc orphan after strip
    if (/\bstatic\b/.test(line) && !line.startsWith('private ')) continue;
    // Match `private` (optionally with `static`/`final` modifiers we skip),
    // then a type (possibly with generics), then an identifier ending with `;`.
    const m = line.match(/^private\s+(?:static\s+)?(?:final\s+)?[\w<>?,.\s]+?\s+(\w+)\s*(?:=\s*[^;]+)?;\s*$/);
    if (m) fields.add(m[1]);
  }
  return fields;
}

// ── TS interface field extractor ─────────────────────────────────────────────
// Captures field names declared inside `export interface XYZDto { ... }`.
// Supports comments, optional `?:`, multi-line union types (string | null).
function extractTsInterfaces(filePath) {
  const src = readFileSync(filePath, 'utf8');
  const out = new Map(); // interfaceName → Set<fieldName>

  // matchAll iterates over each `export interface X { ... }` block.
  const pattern = /export\s+interface\s+(\w+)\s*\{([\s\S]*?)\n\}/g;
  for (const m of src.matchAll(pattern)) {
    const name = m[1];
    if (!name.endsWith('Dto')) continue;
    const body = m[2];
    const fields = new Set();
    // Strip block comments inside the body before parsing.
    const cleanBody = body.replace(/\/\*[\s\S]*?\*\//g, '');
    for (const rawLine of cleanBody.split('\n')) {
      const line = rawLine.trim();
      if (!line || line.startsWith('//')) continue;
      // Match `name:` or `name?:` at line start. Identifier matches \w+
      // including the optional `?`.
      const fm = line.match(/^(\w+)\s*\??\s*:/);
      if (fm) fields.add(fm[1]);
    }
    out.set(name, fields);
  }
  return out;
}

// ── Walk frontend interfaces ────────────────────────────────────────────────
const tsFiles = readdirSync(FRONTEND_MODULES_DIR)
  .filter((f) => f.endsWith('.ts'))
  .map((f) => join(FRONTEND_MODULES_DIR, f));

const allTsInterfaces = new Map(); // DtoName → { file, fields }
for (const f of tsFiles) {
  for (const [name, fields] of extractTsInterfaces(f)) {
    allTsInterfaces.set(name, { file: f, fields });
  }
}

// ── Compare ──────────────────────────────────────────────────────────────────
function backendNameFor(dtoName) {
  if (manualMap.has(dtoName)) return manualMap.get(dtoName);
  return dtoName.replace(/Dto$/, 'Response');
}

const violations = [];
const skipped = [];

for (const [dtoName, { file, fields: frontendFields }] of allTsInterfaces) {
  if (ignoreDtos.has(dtoName)) {
    skipped.push({ dtoName, reason: 'explicitly ignored' });
    continue;
  }
  const backendName = backendNameFor(dtoName);
  if (backendName === null || backendName === '') {
    // Manual map sentinel "" means "skip — pure frontend type".
    skipped.push({ dtoName, reason: 'no backend counterpart' });
    continue;
  }
  const backendPath = javaByName.get(backendName);
  if (!backendPath) {
    skipped.push({ dtoName, reason: `backend file ${backendName}.java not found` });
    continue;
  }

  const backendFields = extractJavaFields(backendPath);
  const allow = allowList[dtoName] ?? {};
  const allowedFrontendOnly = new Set(allow.frontendOnly ?? []);
  const allowedBackendOnly = new Set(allow.backendOnly ?? []);

  const frontendOnly = [...frontendFields].filter(
    (f) => !backendFields.has(f) && !allowedFrontendOnly.has(f),
  );
  const backendOnly = [...backendFields].filter(
    (f) => !frontendFields.has(f) && !allowedBackendOnly.has(f),
  );

  if (frontendOnly.length || backendOnly.length) {
    violations.push({
      dtoName,
      backendName,
      tsFile: relative(REPO_ROOT, file),
      javaFile: relative(REPO_ROOT, backendPath),
      frontendOnly,
      backendOnly,
    });
  }
}

// ── Report ───────────────────────────────────────────────────────────────────
console.log(`Checking ${allTsInterfaces.size} *Dto interfaces against backend *Response shapes...\n`);

if (violations.length === 0) {
  console.log('✓ No DTO drift detected.\n');
  if (skipped.length) {
    console.log(`(${skipped.length} skipped — see dto-drift.config.json)`);
  }
  process.exit(0);
}

for (const v of violations) {
  console.log(`✗ ${v.dtoName} ↔ ${v.backendName}`);
  console.log(`    ts:   ${v.tsFile}`);
  console.log(`    java: ${v.javaFile}`);
  if (v.frontendOnly.length) {
    console.log(`    Frontend declares fields backend doesn't have (silent-drop drift — Jackson ignores):`);
    for (const f of v.frontendOnly) console.log(`      - ${f}`);
  }
  if (v.backendOnly.length) {
    console.log(`    Backend ships fields frontend doesn't declare (missed-surface drift):`);
    for (const f of v.backendOnly) console.log(`      - ${f}`);
  }
  console.log('');
}

console.log(`✗ ${violations.length} DTO drift violation(s) found.\n`);
console.log('  Fix options:');
console.log('    1. Align the frontend Dto and backend Response field sets.');
console.log('    2. If the asymmetry is intentional, add to');
console.log('       cia-frontend/scripts/dto-drift.config.json under allowList:');
console.log('         "PolicyDto": { "frontendOnly": [...], "backendOnly": [...] }');
console.log('       Put a one-line reason in the git commit message.\n');

process.exit(1);
