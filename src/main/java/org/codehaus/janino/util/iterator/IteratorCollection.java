package org.codehaus.janino.util.iterator;

import io.github.nanoforged.adpters.janino.api.anno.Modified;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

@Modified
public class IteratorCollection<T> extends AbstractCollection<T> {

    private final Iterator<T> delegate;

    private final List<T> cache = new ArrayList<>();

    public IteratorCollection(Iterator<T> iterator) {
        this.delegate = iterator;
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {

            private int nextIndex;

            @Override
            public boolean hasNext() {
                return this.nextIndex < cache.size() || delegate.hasNext();
            }

            @Override
            public T next() {
                if (this.nextIndex < cache.size()) {
                    return cache.get(this.nextIndex++);
                }
                if (!delegate.hasNext()) {
                    throw new NoSuchElementException();
                }
                T t = delegate.next();
                cache.add(t);
                this.nextIndex++;
                return t;
            }
        };
    }

    @Override
    public int size() {
        int size = 0;
        for (T ignored : this) {
            size++;
        }
        return size;
    }
}