// Runtime "preact11-htm3": what a job module receives as `runtime`.
import { h } from 'preact';
import htm from 'htm';

export { h, render, Fragment, createContext, createRef, cloneElement, toChildArray } from 'preact';
export {
  useState, useEffect, useLayoutEffect, useMemo, useCallback, useRef, useReducer, useContext, useId,
} from 'preact/hooks';

export const html = htm.bind(h);
