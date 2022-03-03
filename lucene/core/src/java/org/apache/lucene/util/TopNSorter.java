package org.apache.lucene.util;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class TopNSorter<T> {
  private final Comparator<T> comparator;
  private final int maxSize;

  private int size;
  private T[] buffer;
  private PriorityQueue<T> pq;

  public TopNSorter(Comparator<T> comparator, int maxSize) {
    this.comparator = comparator;
    this.maxSize = maxSize;

    @SuppressWarnings("unchecked")
    final T[] b = (T[]) new Object[32];
    buffer = b;
  }

  public T insertWithOverflow(T element) {
    if (size == maxSize) {
      createHeap();
    }

    if (pq != null) {
      T bumped = pq.insertWithOverflow(element);
      assert bumped != null;
      return bumped;
    } else {
      assert buffer != null && size < maxSize && size != -1;
      buffer = ArrayUtil.grow(buffer,size + 1);
      buffer[size] = element;
      size++;
      return null;
    }
  }

  public T getTop() {
    if (pq == null) {
      return null;
    }
    return pq.top();
  }

  public T updateTop() {
    if (pq == null) {
      return null;
    }
    return pq.updateTop();
  }

  public T updateTop(T newTop) {
    if (pq == null) {
      return null;
    }
    return pq.updateTop(newTop);
  }

  public List<T> getSorted() {
    if (buffer != null) {
      Arrays.sort(buffer, 0, size, comparator.reversed());
      return Arrays.asList(buffer).subList(0, size);
    } else {
      assert pq.size() == maxSize;
      @SuppressWarnings("unchecked")
      T[] result = (T[]) new Object[maxSize];
      for (int i = maxSize - 1; i >= 0; i--) {
        result[i] = pq.pop(); // nocommit: this is weird since it clears out the queue
      }
      return Arrays.asList(result);
    }
  }

  private void createHeap() {
    assert pq == null;
    assert buffer != null;
    assert size != -1;
    pq =
      new PriorityQueue<>(maxSize, buffer, size) {
        @Override
        protected boolean lessThan(T a, T b) {
          return comparator.compare(a, b) < 0;
        }
      };
    assert pq.size() == size;
    buffer = null;
    size = -1;
  }
}
