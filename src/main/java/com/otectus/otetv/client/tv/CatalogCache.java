package com.otectus.otetv.client.tv;

import com.otectus.otetv.network.C2S;
import com.otectus.otetv.network.OteTvNetwork;
import com.otectus.otetv.network.S2C;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/** Client copy of the paginated, revisioned channel catalog (section 18.3). */
public final class CatalogCache {
    private final TreeMap<Integer, List<S2C.ChannelEntry>> pages = new TreeMap<>();
    private long revision = -1;
    private int pageCount;
    private long lastRequest;

    public synchronized void request(boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastRequest < 2_000_000_000L) {
            return;
        }
        lastRequest = now;
        OteTvNetwork.sendToServer(new C2S.RequestCatalog(0));
    }

    public synchronized void accept(S2C.ChannelCatalog page) {
        if (page.revision() != revision) {
            pages.clear();
            revision = page.revision();
        }
        pageCount = page.pageCount();
        pages.put(page.page(), page.entries());
        if (page.page() + 1 < page.pageCount() && !pages.containsKey(page.page() + 1)) {
            OteTvNetwork.sendToServer(new C2S.RequestCatalog(page.page() + 1));
        }
    }

    public synchronized boolean isComplete() {
        return revision >= 0 && pages.size() >= pageCount;
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized List<S2C.ChannelEntry> entries() {
        List<S2C.ChannelEntry> all = new ArrayList<>();
        pages.values().forEach(all::addAll);
        all.sort(Comparator.comparingInt(S2C.ChannelEntry::number));
        return all;
    }

    public synchronized Optional<S2C.ChannelEntry> byNumber(int number) {
        return entries().stream().filter(e -> e.number() == number).findFirst();
    }

    public static String label(S2C.ChannelEntry e) {
        return e.translatable() ? I18n.get(e.displayName()) : e.displayName();
    }

    /** Snapshot labels may be translation keys (servers lack mod language files). */
    public static String localize(String label) {
        return I18n.exists(label) ? I18n.get(label) : label;
    }

    public synchronized void clear() {
        pages.clear();
        revision = -1;
        pageCount = 0;
    }
}
