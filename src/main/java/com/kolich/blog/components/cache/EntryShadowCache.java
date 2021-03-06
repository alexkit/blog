/**
 * Copyright (c) 2015 Mark S. Kolich
 * http://mark.koli.ch
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package com.kolich.blog.components.cache;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.eventbus.Subscribe;
import com.kolich.blog.components.cache.bus.BlogEventBus;
import com.kolich.blog.entities.Entry;
import com.kolich.blog.entities.gson.PagedContent;
import com.kolich.blog.protos.Events;
import curacao.annotations.Component;
import curacao.annotations.Injectable;
import curacao.annotations.Required;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * This cache is similar to {@link EntryCache} except this in-memory cache maps an {@link Entry}
 * entity to the set of {@link Entry} entities that logically come before it.  Before here is defined
 * in terms of commit history; if you have entries [A, B, C] in sorted order, the set of commits that
 * were committed to the repo "before A" is [B, C] where "A" is the latest/newest.
 *
 * This is used exclusively for the "Load More" button on the blog homepage.  We want to be able to
 * fetch the list of entries that come before (is older than) a given entry in constant time.
 */
@Component
public final class EntryShadowCache {

    private static final Logger logger__ = getLogger(EntryShadowCache.class);

    /**
     * A private reference to the internal entry cache.
     */
    private final EntryCache entryCache_;

    /**
     * An internal map that maps each SHA-1 commit hash to a list of content/entries that was written before
     * that commit.  For example if A, B, C, D are a list of commits in order, then list [A, B, C, D] will
     * be translated and cached here as:
     *
     *   A -> [B, C, D]
     *   B -> [C, D]
     *   C -> [D]
     *   D -> []
     *
     * This is so the lookup of "the set of entities that came before a given entity" can be done in
     * constant time O(1).
     */
    private final Multimap<String, Entry> shadowCache_;

    @Injectable
    public EntryShadowCache(@Required final EntryCache entryCache,
                            @Required final BlogEventBus eventBus) {
        entryCache_ = entryCache;
        shadowCache_ = LinkedListMultimap.create(); // Preserves insertion order
        eventBus.register(this);
    }

    /**
     * Fires when the global {@link EntryCache} is built and ready for reading.
     */
    @Subscribe
    public synchronized final void onEntryCacheReady(final Events.EntryCacheReadyEvent e) {
        final List<Entry> allEntries = entryCache_.getAll();
        // Clear the current shadow cache.
        shadowCache_.clear();
        // Construct a map which maps each ordered commit hash to the list of content that comes after it.  Note,
        // a SortedSetMultimap could have been used here, but that implementation depends on the natural ordering of
        // the keys and values in the map.  In this case, the ordering isn't the "natural" ordering but is rather
        // dictated by time (e.g., given an entity X, give me all of the stuff older than it in constant time).
        final Multimap<String, Entry> shadowCache = LinkedListMultimap.create();
        for (final Entry entity : allEntries) {
            boolean includeNext = false;
            for (final Entry inner : allEntries) {
                if (entity.getName().equals(inner.getName())) {
                    includeNext = true;
                } else if (includeNext) {
                    shadowCache.put(entity.getCommit(), inner);
                }
            }
        }
        shadowCache_.putAll(shadowCache);
    }

    /**
     * Returns all cached content that was committed to the repo before (older, prior to) the given
     * commit, not including the commit itself.
     */
    public synchronized final PagedContent<Entry> getAllBefore(@Nullable final String commit,
                                                               @Nullable final Integer limit) {
        final PagedContent<Entry> result;
        final Collection<Entry> shadow = shadowCache_.get(commit);
        final String firstCommit = Iterables.getFirst(shadowCache_.keySet(), null);
        if (shadow.isEmpty()) {
            result = new PagedContent<>(ImmutableList.of(), firstCommit, shadowCache_.keySet().size());
        } else {
            final List<Entry> before = ImmutableList.copyOf(shadow);
            final int endIndex = (limit != null && limit > 0 && limit <= before.size()) ? limit : before.size();
            final List<Entry> sublist = before.subList(0, endIndex);
            result = new PagedContent<>(sublist, firstCommit, before.size() - sublist.size());
        }
        return result;
    }

}
