/*
 * Copyright 2022 John Grosh (jagrosh).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.queue;

import com.jagrosh.jmusicbot.audio.QueuedTrack;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 *
 * @author Wolfgang Schwendtbauer
 * @param <T>
 */
public abstract class AbstractQueue<T extends Queueable>
{
    /**
     * Creates a new queue, optionally copying state from an existing queue.
     * 
     * @param queue The previous queue to copy state from, or null for initial creation
     * @param maxHistorySize The maximum history size (only used when queue is null)
     */
    protected AbstractQueue(AbstractQueue<T> queue, int maxHistorySize)
    {
        // Use ArrayList for O(1) random access (display, shuffle, etc.)
        // Copy the list when switching queue types to avoid shared mutable state
        this.list = queue != null ? new ArrayList<>(queue.getList()) : new ArrayList<>();
        // Reuse history when switching queue types; always apply configured max size so transitions
        // (including maxHistorySize=0 disabled mode) take effect immediately.
        this.history = queue != null ? queue.getHistory() : new PlaybackHistory<>(maxHistorySize, qt -> qt instanceof QueuedTrack ? ((QueuedTrack) qt).getTrack().getIdentifier() : qt.getIdentifier());
        this.history.setMaxSize(maxHistorySize);
    }

    protected final List<T> list;
    protected final PlaybackHistory<T> history;

    public abstract int add(T item);

    public synchronized void addAt(int index, T item)
    {
        if(index >= list.size())
            list.add(item);
        else
            list.add(index, item);
    }

    public synchronized int size() {
        return list.size();
    }

    public synchronized T pull() {
        if (list.isEmpty())
            return null;
        return list.remove(0);
    }

    public synchronized void addToHistory(T item)
    {
        history.add(item);
    }

    public synchronized void setMaxHistorySize(int size)
    {
        history.setMaxSize(size);
    }

    /**
     * Clears playback history entries.
     */
    public synchronized void clearHistory()
    {
        history.clear();
    }

    public synchronized T removeLastPlayed()
    {
        return history.removeFirst();
    }

    /**
     * Removes the history entry at the given index (0 = most recent).
     * Used when a user replays a track from history so it is not retained.
     *
     * @param index The history index to remove
     * @return The removed item, or null if index is out of range
     */
    public synchronized T removeFromHistoryAt(int index)
    {
        return history.removeAt(index);
    }

    /**
     * Removes the first history entry that matches the predicate.
     * Used when a track starts playing to avoid duplicate entries in history.
     *
     * @param predicate The condition to match (e.g. same track identifier)
     * @return true if an entry was removed, false otherwise
     */
    public synchronized boolean removeFromHistoryFirstMatch(Predicate<T> predicate)
    {
        return history.removeFirstMatching(predicate);
    }

    /**
     * Rewinds the queue by taking the last played item from history
     * and optionally pushing the current item back to the front of the queue.
     * @param current The currently playing item to push back to the queue
     * @return The previous item to play, or null if history is empty
     */
    public synchronized T rewind(T current)
    {
        T prev = history.removeFirst();
        if (prev != null && current != null)
        {
            list.add(0, current);
        }
        return prev;
    }

    public synchronized boolean isEmpty()
    {
        return list.isEmpty();
    }

    /**
     * @return a snapshot of the queue; changes to it do not affect the queue
     */
    public synchronized List<T> getList()
    {
        return List.copyOf(list);
    }

    public synchronized PlaybackHistory<T> getHistory()
    {
        return history;
    }

    public synchronized T get(int index) {
        return list.get(index);
    }

    public synchronized T remove(int index)
    {
        return list.remove(index);
    }

    public synchronized int removeAll(long identifier)
    {
        int count = 0;
        for(int i=list.size()-1; i>=0; i--)
        {
            if(list.get(i).getIdentifier()==identifier)
            {
                list.remove(i);
                count++;
            }
        }
        return count;
    }

    public synchronized void clear()
    {
        list.clear();
    }

    public synchronized void clearAll()
    {
        list.clear();
        history.clear();
    }

    public synchronized int shuffle(long identifier)
    {
        List<Integer> iset = new ArrayList<>();
        for(int i=0; i<list.size(); i++)
        {
            if(identifier == 0 || list.get(i).getIdentifier()==identifier)
                iset.add(i);
        }
        for(int j=0; j<iset.size(); j++)
        {
            int first = iset.get(j);
            int second = iset.get((int)(Math.random()*iset.size()));
            T temp = list.get(first);
            list.set(first, list.get(second));
            list.set(second, temp);
        }
        return iset.size();
    }

    public synchronized void skip(int number)
    {
        if (number > 0) {
            list.subList(0, Math.min(number, list.size())).clear();
        }
    }

    /**
     * Move an item to a different position in the list
     * @param from The position of the item
     * @param to The new position of the item
     * @return the moved item
     */
    public synchronized T moveItem(int from, int to)
    {
        T item = list.remove(from);
        list.add(to, item);
        return item;
    }
}
