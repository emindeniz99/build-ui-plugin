// Types for build-ui form modules: the mount contract (README, "The module
// contract"; docs/design.md section 2) and what each runtime passes as
// `runtime`. Self-contained, so a copy works in any repository.
//
// In a module file:
//
//   // @ts-check
//   /** @type {import('./build-ui').MountFor<'preact11-htm3', { branch: string }>} */
//   export default function mount({ root, ui, runtime: { html, render, useState } }) { ... }

/** JSON as the controller stores it. */
export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

/** The controller's JSON Schema subset (README, "Validation and limits"), plus annotations and x- extensions. */
export interface JsonSchema {
  type?: JsonSchemaType | JsonSchemaType[];
  enum?: Json[];
  const?: Json;
  required?: string[];
  properties?: { [name: string]: JsonSchema | boolean };
  additionalProperties?: JsonSchema | boolean;
  minProperties?: number;
  maxProperties?: number;
  items?: JsonSchema | boolean;
  minItems?: number;
  maxItems?: number;
  uniqueItems?: boolean;
  minLength?: number;
  maxLength?: number;
  pattern?: string;
  minimum?: number;
  maximum?: number;
  exclusiveMinimum?: number;
  exclusiveMaximum?: number;
  title?: string;
  description?: string;
  default?: Json;
  examples?: Json[];
  format?: string;
  readOnly?: boolean;
  writeOnly?: boolean;
  deprecated?: boolean;
  $schema?: string;
  $id?: string;
  $comment?: string;
  'x-git-ref'?: true | 'branch' | 'tag' | 'any';
  'x-show-if'?: { [sibling: string]: Json };
  'x-widget'?: 'textarea';
  [extension: `x-${string}`]: unknown;
}
export type JsonSchemaType = 'string' | 'number' | 'integer' | 'boolean' | 'object' | 'array' | 'null';

// ---- data providers ------------------------------------------------------

export interface JenkinsRunsArgs { job: string; limit?: number; parameter?: string }
export interface JenkinsRun {
  number: number;
  displayName: string;
  description: string | null;
  /** null while the build runs. */
  result: 'SUCCESS' | 'UNSTABLE' | 'FAILURE' | 'NOT_BUILT' | 'ABORTED' | null;
  building: boolean;
  timestamp: number;
  duration: number;
  url: string;
  /** Present when the query names `parameter`: that run's value of it, or null. */
  config?: Json;
}

export interface GitRefsArgs {
  job: string;
  branches?: string;
  tags?: string;
  sort?: 'name' | 'version-desc';
  limit?: number;
}
export interface GitRef { name: string; sha: string; repository: string }
export interface GitRefs { branches: GitRef[]; tags: GitRef[] }

export interface ArgoCdAppArgs { app: string; server?: string }
export interface ArgoCdApp {
  name: string | null;
  project: string | null;
  namespace: string | null;
  health: string | null;
  sync: string | null;
  revision: string | null;
  operation: string | null;
  reconciledAt: string | null;
  images: string[];
}

/** The providers the plugin ships; other plugins add their own ids. */
export interface Providers {
  jenkinsRuns: { args: JenkinsRunsArgs; answer: JenkinsRun[] };
  gitRefs: { args: GitRefsArgs; answer: GitRefs };
  argoCdApp: { args: ArgoCdAppArgs; answer: ArgoCdApp };
}

export interface QueryOptions { signal?: AbortSignal }

// ---- the contract --------------------------------------------------------

/** The value channel between the form and what Jenkins submits. */
export interface Ui<V = any> {
  /** The current value, parsed from the JSON field. */
  readonly value: V;
  /** Replaces the value the form submits. */
  setValue(next: V): void;
  /** Calls a data provider on the controller as the current user; rejects with the provider's error message. */
  query<P extends keyof Providers>(provider: P, args: Providers[P]['args'], options?: QueryOptions): Promise<Providers[P]['answer']>;
  /** Any other provider id; a shipped id never falls through to this, so its arguments stay checked. */
  query<T = any, P extends string = string>(provider: P & (P extends keyof Providers ? never : unknown),
    args?: { [key: string]: Json | undefined }, options?: QueryOptions): Promise<T>;
  /** Full name of the job, '' outside a job. */
  readonly job: string;
  /** The parameter's name, as in params.NAME. */
  readonly name: string;
  /** The parsed schema, or null without one (and always null in a uiPanel). */
  readonly schema: JsonSchema | null;
}

export interface MountContext<R = Runtime, V = any> {
  /** The element to draw into, inside the form's shadow root. */
  root: HTMLElement;
  ui: Ui<V>;
  runtime: R;
  /** Aborts when the form goes away. */
  signal: AbortSignal;
}

export type Cleanup = () => void;
export type Mount<R = Runtime, V = any> = (context: MountContext<R, V>) => void | Cleanup | Promise<void | Cleanup>;

/** The mount function of a module for one runtime id. */
export type MountFor<Id extends RuntimeId, V = any> = Mount<Runtimes[Id], V>;

// ---- runtimes ------------------------------------------------------------

/** A Preact node; what html`` returns. */
export type VNode = { type: unknown; props: { [key: string]: unknown }; key?: unknown };
export type ComponentChild = VNode | string | number | bigint | boolean | null | undefined | ComponentChild[];
export type FunctionComponent<P = {}> = (props: P & { children?: ComponentChild }) => ComponentChild;
export interface Ref<T> { current: T }
export interface Context<T> { Provider: FunctionComponent<{ value: T }>; Consumer: unknown }
type StateUpdater<S> = (value: S | ((previous: S) => S)) => void;
type Inputs = readonly unknown[];

/** `preact11-htm3`: Preact 11.0.1 and htm 3.1.1. */
export interface Preact11Htm3 {
  html(strings: TemplateStringsArray, ...values: unknown[]): ComponentChild;
  h(type: string | FunctionComponent<any>, props?: { [key: string]: unknown } | null, ...children: ComponentChild[]): VNode;
  render(vnode: ComponentChild, parent: Element | DocumentFragment): void;
  Fragment: FunctionComponent;
  createContext<T>(defaultValue: T): Context<T>;
  createRef<T = any>(): Ref<T | null>;
  cloneElement(vnode: VNode, props?: { [key: string]: unknown } | null, ...children: ComponentChild[]): VNode;
  toChildArray(children: ComponentChild): (VNode | string | number)[];
  useState<S>(initial: S | (() => S)): [S, StateUpdater<S>];
  useState<S = undefined>(): [S | undefined, StateUpdater<S | undefined>];
  useEffect(effect: () => void | (() => void), inputs?: Inputs): void;
  useLayoutEffect(effect: () => void | (() => void), inputs?: Inputs): void;
  useMemo<T>(factory: () => T, inputs: Inputs | undefined): T;
  useCallback<T extends Function>(callback: T, inputs: Inputs): T;
  useRef<T>(initial: T): Ref<T>;
  useRef<T>(initial: T | null): Ref<T | null>;
  useRef<T = undefined>(): Ref<T | undefined>;
  useReducer<S, A>(reducer: (state: S, action: A) => S, initial: S): [S, (action: A) => void];
  useReducer<S, A, I>(reducer: (state: S, action: A) => S, initialArg: I, init: (arg: I) => S): [S, (action: A) => void];
  useContext<T>(context: Context<T>): T;
  useId(): string;
}

/** Alpine 3.17.4's global object; only the parts forms use are typed. */
export interface AlpineGlobal {
  data(name: string, factory: (...args: any[]) => object): void;
  store(name: string, value?: unknown): any;
  /** Throws in build-ui: it would start Alpine on the whole Jenkins page. Return start(root) instead. */
  start(): never;
  [member: string]: any;
}

/** `alpine3-csp` and `alpine3`. */
export interface AlpineRuntime {
  Alpine: AlpineGlobal;
  /** Starts Alpine inside root only; returns the cleanup that stops it. */
  start(root: Element): Cleanup;
}

/** `schema-form` draws the form itself; a module is not needed. */
export interface SchemaFormRuntime {
  mount: Mount<SchemaFormRuntime>;
}

/** `vue3`: Vue 3.5's whole API, untyped here; Vue's own types apply where `vue` is installed. */
export type Vue3Runtime = { [member: string]: any };

/** What `runtime` holds, per runtime id (contract.json lists the ids). */
export interface Runtimes {
  none: {};
  'preact11-htm3': Preact11Htm3;
  'schema-form': SchemaFormRuntime;
  'alpine3-csp': AlpineRuntime;
  alpine3: AlpineRuntime;
  vue3: Vue3Runtime;
}
export type RuntimeId = keyof Runtimes;
export type Runtime = Runtimes[RuntimeId];
