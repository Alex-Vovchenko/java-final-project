package searchengine.services;

import org.jsoup.Jsoup;
import org.jsoup.UnsupportedMimeTypeException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import searchengine.model.*;
import searchengine.repository.LemmaRepository;
import searchengine.repository.MyIndexRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.atomic.AtomicBoolean;

public class PageCrawler extends RecursiveTask<Void> {
    private final Site site;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private final LemmaRepository lemmaRepository;
    private final MyIndexRepository myIndexRepository;
    private final LemmaFinder lemmaFinder;

    private final String url;
    private final String userAgent;
    private final String referer;
    private final long delay;
    private final AtomicBoolean stopRequested;

    private static final ConcurrentHashMap<String, Object> lemmaLocks = new ConcurrentHashMap<>();

    private static Object getLockForLemma(String lemma, Integer siteId) {
        String key = siteId + "_" + lemma;
        return lemmaLocks.computeIfAbsent(key, k -> new Object());
    }

    private static final ConcurrentHashMap<String, Object> pageLocks = new ConcurrentHashMap<>();

    private static Object getLockForPage(Integer siteId, String path) {
        String key = siteId + "_" + path;
        return pageLocks.computeIfAbsent(key, k -> new Object());
    }

    public PageCrawler(Site site,
                       PageRepository pageRepository,
                       SiteRepository siteRepository,
                       LemmaRepository lemmaRepository,
                       MyIndexRepository myIndexRepository,
                       LemmaFinder lemmaFinder,
                       String url,
                       String userAgent,
                       String referer,
                       long delay,
                       AtomicBoolean stopRequested) {
        this.site = site;
        this.pageRepository = pageRepository;
        this.siteRepository = siteRepository;
        this.lemmaRepository = lemmaRepository;
        this.myIndexRepository = myIndexRepository;
        this.lemmaFinder = lemmaFinder;
        this.url = url;
        this.userAgent = userAgent;
        this.referer = referer;
        this.delay = delay;
        this.stopRequested = stopRequested;

    }

    @Override
    protected Void compute() {
        if (stopRequested.get()) {
            site.setLastError("Indexing stopped by user");
            siteRepository.save(site);
            return null;
        }
        String path;
        try {
            URI uri = new URI(url);
            path = uri.getPath();
            path = (path == null || path.isEmpty()) ? "/" : path;
        } catch (URISyntaxException e) {
            site.setLastError("Error URI: " + e.getMessage());
            siteRepository.save(site);
            return null;
        }

        System.out.println("URL found: " + url + " -> normalized path: " + path);
        System.out.println("Checking duplicate for path: " + path + " on site " + site.getUrl());

        try {
            Thread.sleep(delay);
            if (stopRequested.get()) {
                System.out.println("Stop requested after sleep; terminating task.");
                return null;
            }
            Document doc;
            try {
                doc = Jsoup.connect(url)
                        .userAgent(userAgent)
                        .referrer(referer)
                        .get();
            } catch (UnsupportedMimeTypeException e) {
                System.out.println("Skipping page due to unsupported MIME type: " + e.getMessage());
                return null;
            }

            String content = doc.html();
            if (stopRequested.get()) {
                System.out.println("Stop requested after loading HTML; terminating task.");
                return null;
            }
            int statusCode = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .referrer(referer)
                    .execute().statusCode();

            Page page = new Page();
            page.setSite(site);
            page.setPath(path);
            page.setContent(content);
            page.setCode(statusCode);
            synchronized (getLockForPage(site.getId(), path)) {
                if (pageRepository.existsBySiteIdAndPath(site.getId(), path)) {
                    System.out.println("Duplicate detected at saving stage for path: " + path);
                    return null;

                } else {
                    pageRepository.save(page);
                }
            }

            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);

            if (stopRequested.get()) {
                System.out.println("Stop requested after loading HTML; terminating task.");
                return null;
            }

            Map<String, Integer> lemmaCounts = lemmaFinder.getLemmas(content);
            for (Map.Entry<String, Integer> entry : lemmaCounts.entrySet()) {
                String lemmaStr = entry.getKey();
                int countOnPage = entry.getValue();

                Lemma lemmaRecord;
                synchronized (getLockForLemma(lemmaStr, site.getId())) {
                    lemmaRecord = lemmaRepository.findByLemmaAndSite(lemmaStr, site);
                    if (lemmaRecord == null) {
                        lemmaRecord = new Lemma();
                        lemmaRecord.setSite(site);
                        lemmaRecord.setLemma(lemmaStr);
                        lemmaRecord.setFrequency(1);
                        lemmaRepository.save(lemmaRecord);
                    } else {
                        lemmaRecord.setFrequency(lemmaRecord.getFrequency() + 1);
                        lemmaRepository.save(lemmaRecord);
                    }
                }

                MyIndex indexRecord = new MyIndex();
                indexRecord.setPage(page);
                indexRecord.setLemma(lemmaRecord);
                indexRecord.setRank(countOnPage);
                myIndexRepository.save(indexRecord);
            }

            if (stopRequested.get()) {
                System.out.println("Stop requested after loading HTML; terminating task.");
                return null;
            }

            List<PageCrawler> tasks = new ArrayList<>();
            Elements links = doc.select("a[href]");
            for (Element link : links) {
                String thisLink = link.absUrl("href");
                String baseDomain = site.getUrl();

                if (DomainUrlVerification.isValidURL(thisLink) && DomainUrlVerification.isSameDomain(baseDomain, thisLink)) {
                    try {
                        URI linkUri = new URI(thisLink);
                        String linkPath = linkUri.getPath();
                        linkPath = (linkPath == null || linkPath.isEmpty()) ? "/" : linkPath;
                        synchronized (getLockForPage(site.getId(), linkPath)) {
                            if (!pageRepository.existsBySiteIdAndPath(site.getId(), linkPath)) {
                                tasks.add(new PageCrawler(site, pageRepository, siteRepository,
                                        lemmaRepository, myIndexRepository, lemmaFinder,
                                        thisLink, userAgent, referer, delay, stopRequested));
                            } else {
                                System.out.println("Duplicate found for link: " + thisLink + " -> path: " + linkPath);
                            }
                        }
                    } catch (URISyntaxException ex) {
                        System.out.println("Error processing link: " + thisLink + " (" + ex.getMessage() + ")");
                    }
                } else {
                    if (!DomainUrlVerification.isValidURL(thisLink)) {
                        System.out.println("Skipping link (invalid URL): " + thisLink);
                    } else if (!DomainUrlVerification.isSameDomain(baseDomain, thisLink)) {
                        System.out.println("Skipping link (different domain): " + thisLink + "; base domain: " + baseDomain);
                    } else if (!thisLink.startsWith(baseDomain)) {
                        System.out.println("Skipping link (does not start with base domain): " + thisLink + "; base domain: " + baseDomain);
                    } else {
                        System.out.println("Skipping link (unspecified reason): " + thisLink);
                    }
                }
            }
            invokeAll(tasks);
        } catch (IOException | InterruptedException e) {
            site.setLastError("Error loading page: " + e.getMessage());
            siteRepository.save(site);
        }
        return null;
    }

}