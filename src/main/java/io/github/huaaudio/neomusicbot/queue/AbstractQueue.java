/*
 * Modified by Huaaudio for independent Bilibili/Discord development (2026).
 * @author Wolfgang Schwendtbauer
 */
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
package io.github.huaaudio.neomusicbot.queue;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Base queue with synchronized mutations and immutable read snapshots.
 */
public abstract class AbstractQueue<T extends Queueable>
{
    protected final List<T> list;

    protected AbstractQueue(AbstractQueue<T> queue)
    {
        this.list = queue != null ? new LinkedList<>(queue.getList()) : new LinkedList<>();
    }

    public abstract int add(T item);

    public synchronized void addAt(int index, T item)
    {
        if(index >= list.size())
            list.add(item);
        else
            list.add(Math.max(0, index), item);
    }

    public synchronized int size()
    {
        return list.size();
    }

    public synchronized T pull()
    {
        return list.remove(0);
    }

    public synchronized boolean isEmpty()
    {
        return list.isEmpty();
    }

    /** Returns an immutable point-in-time snapshot for pagination and display. */
    public synchronized List<T> getList()
    {
        return List.copyOf(list);
    }

    public synchronized T get(int index)
    {
        return list.get(index);
    }

    public synchronized T remove(int index)
    {
        return list.remove(index);
    }

    public synchronized int removeAll(long identifier)
    {
        int count = 0;
        for(int i = list.size() - 1; i >= 0; i--)
        {
            if(list.get(i).getIdentifier() == identifier)
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

    public synchronized int shuffle(long identifier)
    {
        List<Integer> indexes = new ArrayList<>();
        for(int i = 0; i < list.size(); i++)
        {
            if(list.get(i).getIdentifier() == identifier)
                indexes.add(i);
        }
        for(int first : indexes)
        {
            int second = indexes.get(ThreadLocalRandom.current().nextInt(indexes.size()));
            T item = list.get(first);
            list.set(first, list.get(second));
            list.set(second, item);
        }
        return indexes.size();
    }

    public synchronized void skip(int number)
    {
        if(number > 0)
            list.subList(0, Math.min(number, list.size())).clear();
    }

    public synchronized T moveItem(int from, int to)
    {
        T item = list.remove(from);
        list.add(to, item);
        return item;
    }
}
