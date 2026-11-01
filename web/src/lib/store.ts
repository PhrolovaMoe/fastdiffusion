import { useSyncExternalStore } from 'react'

// A minimal external store: components subscribe through selectors that return stable references.
export interface Store<T> {
  get(): T
  set(update: Partial<T> | ((s: T) => Partial<T>)): void
  subscribe(listener: () => void): () => void
  use<U>(selector: (s: T) => U): U
}

export function createStore<T extends object>(initial: T): Store<T> {
  let state = initial
  const listeners = new Set<() => void>()
  const store: Store<T> = {
    get: () => state,
    set(update) {
      const patch = typeof update === 'function' ? update(state) : update
      state = { ...state, ...patch }
      listeners.forEach((l) => l())
    },
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    use: (selector) =>
      useSyncExternalStore(
        store.subscribe,
        () => selector(state),
        () => selector(initial),
      ),
  }
  return store
}

/** A tiny typed event emitter for things that are events rather than state. */
export class Emitter<E extends Record<string, unknown>> {
  private handlers = new Map<keyof E, Set<(v: never) => void>>()

  on<K extends keyof E>(type: K, fn: (v: E[K]) => void): () => void {
    let set = this.handlers.get(type)
    if (!set) this.handlers.set(type, (set = new Set()))
    set.add(fn as (v: never) => void)
    return () => set.delete(fn as (v: never) => void)
  }

  emit<K extends keyof E>(type: K, value: E[K]) {
    this.handlers.get(type)?.forEach((fn) => (fn as (v: E[K]) => void)(value))
  }
}
