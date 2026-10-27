#!/usr/bin/env node
// Type-checks form module files with TypeScript's checkJs, the way an editor
// checks a file that starts with // @ts-check. A module gets the contract's
// types by annotating mount with js/types/build-ui.d.ts:
//
//   /** @type {import('<path to>/build-ui').MountFor<'preact11-htm3'>} */
//   export default function mount({ root, ui, runtime }) { ... }
//
//   pnpm --dir projects/jenkins-ui/js typecheck path/to/form.mjs [more files]
//
// It checks .js and .mjs files only; a module inside a Jenkinsfile or a
// form.html has no file of its own for the compiler to read.
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

export const OPTIONS = {
  allowJs: true,
  checkJs: true,
  noEmit: true,
  target: ts.ScriptTarget.ES2022,
  module: ts.ModuleKind.ESNext,
  moduleResolution: ts.ModuleResolutionKind.Bundler,
  // Forms run in the browser: no Node types, the DOM of the page.
  lib: ['lib.es2022.d.ts', 'lib.dom.d.ts', 'lib.dom.iterable.d.ts'],
  types: [],
  // TypeScript 6 makes strict the default. In plain JavaScript it reports every
  // unannotated parameter and types useState([]) as never[], so all four
  // example forms fail it; the checks that matter here (names and types on ui
  // and runtime, provider arguments) need no strict mode.
  strict: false,
};

/** Returns [{ file, line, column, message }] for the given files and what they import. */
export function typecheck(files) {
  const program = ts.createProgram(files, OPTIONS);
  return ts.getPreEmitDiagnostics(program).map((d) => {
    const message = ts.flattenDiagnosticMessageText(d.messageText, '\n');
    if (!d.file) return { file: null, line: 0, column: 0, message };
    const { line, character } = d.file.getLineAndCharacterOfPosition(d.start);
    return { file: d.file.fileName, line: line + 1, column: character + 1, message };
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.length === 0) {
    console.error('usage: typecheck.mjs <form.mjs> [...]');
    process.exit(2);
  }
  // `pnpm --dir js typecheck <file>` runs this in js/; pnpm puts the caller's directory in INIT_CWD.
  const base = process.env.INIT_CWD ?? process.cwd();
  const files = args.map((f) => path.resolve(base, f));
  const wrong = files.filter((f) => !/\.m?js$/.test(f));
  if (wrong.length) {
    console.error(`typecheck reads .js and .mjs files only: ${wrong.map((f) => path.relative(base, f)).join(', ')}`);
    process.exit(2);
  }
  const problems = typecheck(files);
  for (const p of problems) {
    console.log(p.file ? `${path.relative(base, p.file)}:${p.line}:${p.column}: error: ${p.message}` : `error: ${p.message}`);
  }
  console.log(problems.length ? `${problems.length} error(s) in ${files.length} file(s)` : `${files.length} file(s) checked, no errors`);
  process.exit(problems.length ? 1 : 0);
}
