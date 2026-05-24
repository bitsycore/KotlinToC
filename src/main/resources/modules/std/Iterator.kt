package ktc.std

interface Iterator<T> {
    operator fun hasNext(): Boolean
    operator fun next(): T
}

interface Iterable<T> {
    operator fun iterator(): Iterator<T>
}