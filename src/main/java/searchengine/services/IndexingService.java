package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.dto.indexing.IndexingResponse;
import searchengine.model.*;
import searchengine.config.SitesList;
import searchengine.repository.LemmaRepository;
import searchengine.repository.MyIndexRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IndexingService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final MyIndexRepository myIndexRepository;
    private final SitesList sitesList;
    private final LemmaFinder lemmaFinder;
    private final SiteService siteService;

    private ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private final AtomicBoolean indexingInProgress = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    @Value("${indexing-settings.user-agent}")
    private String userAgent;

    @Value("${indexing-settings.referer}")
    private String referer;

    @Value("${indexing-settings.delay}")
    private long delay;

    public boolean startIndexingResponse() {
        if (!indexingInProgress.compareAndSet(false, true)) {
            return false;
        }
        lemmaFinder.startLemma();
        stopRequested.set(false);
        if (executorService.isShutdown() || executorService.isTerminated()) {
            executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        }

        executorService.submit(() -> startIndexing());

        return true;
    }

    private void startIndexing() {
        for (int i = 0; i < sitesList.getSites().size(); i++) {
            if (siteRepository.existsByUrl(sitesList.getSites().get(i).getUrl())) {
                String siteDetached = sitesList.getSites().get(i).getUrl();
                siteService.deleteSiteData(siteDetached);
            }
        }
        sitesList.getSites().forEach(siteConfig ->
                executorService.submit(() -> indexSite(siteConfig))
        );
    }

    public boolean stopIndexing() {
        if (!indexingInProgress.get()) {
            return false;
        }
        stopRequested.set(true);
        executorService.shutdownNow();

        List<Site> sitesInIndexing = siteRepository.findAll().stream()
                .filter(site -> site.getStatus() == Status.INDEXING)
                .collect(Collectors.toList());
        for (Site site : sitesInIndexing) {
            site.setStatus(Status.FAILED);
            site.setLastError("Indexing stopped by user");
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
        }
        indexingInProgress.set(false);
        lemmaFinder.stopLemma();
        return true;
    }

    private void indexSite(searchengine.config.Site siteConfig) {
        stopRequested.set(false);
        lemmaFinder.startLemma();

        Site site = new Site();
        site.setUrl(siteConfig.getUrl());
        site.setName(siteConfig.getName());
        site.setStatus(Status.INDEXING);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);

        ForkJoinPool pool = new ForkJoinPool();
        PageCrawler crawler = new PageCrawler(site, pageRepository, siteRepository, lemmaRepository, myIndexRepository, lemmaFinder,
                site.getUrl(), userAgent, referer, delay, stopRequested);

        try {
            pool.invoke(crawler);
            if (!stopRequested.get()) {
                site.setStatus(Status.INDEXED);
            } else {
                site.setStatus(Status.FAILED);
                site.setLastError("Indexing stopped by user");
            }
        } catch (Exception e) {
            site.setStatus(Status.FAILED);
            site.setLastError("Bypass error: " + e.getMessage());
        } finally {
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
            pool.shutdown();
        }
    }

    public IndexingResponse indexPageResponse(String url) {
        if (!DomainUrlVerification.isValidURL(url)) {
            return new IndexingResponse(false, "The provided URL is invalid.");
        }

        var siteConfigs = sitesList.getSites();
        boolean validUrl = false;
        searchengine.config.Site matchingSiteConfig = null;

        for (var siteConfig : siteConfigs) {
            if (DomainUrlVerification.isSameDomain(siteConfig.getUrl(), url)) {
                validUrl = true;
                matchingSiteConfig = siteConfig;
                break;
            }
        }
        if (!validUrl) {
            return new IndexingResponse(false, "This page is located outside the sites specified in the configuration file.");
        }

        final searchengine.config.Site finalMatchingSite = matchingSiteConfig;
        executorService.submit(() -> indexPage(url, finalMatchingSite));

        return new IndexingResponse(true);
    }

    private void indexPage(String url, searchengine.config.Site finalMatchingSite) {
        stopRequested.set(false);
        lemmaFinder.startLemma();

        String matchingSiteUrl = finalMatchingSite.getUrl();
        Site site = siteRepository.findByUrl(matchingSiteUrl);
        if (site == null) {
            site = new Site();
            site.setUrl(matchingSiteUrl);
            site.setName(finalMatchingSite.getName());
            site.setStatus(Status.INDEXING);
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
        }
        lemmaFinder.startLemma();

        String path;
        try {
            URI uri = new URI(url);
            path = uri.getPath();
            path = (path == null || path.isEmpty()) ? "/" : path;
        } catch (URISyntaxException e) {
            site.setLastError("Error URI: " + e.getMessage());
            siteRepository.save(site);
            return;
        }

        Page existingPage = pageRepository.findByPathAndSite(path, site);
        if (existingPage != null) {
            deletePageData(existingPage);
        }

        try {
            var connection = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .referrer(referer);
            var response = connection.execute();
            String htmlContent = response.body();

            Page newPage = new Page();
            newPage.setSite(site);
            newPage.setPath(path);
            newPage.setContent(htmlContent);
            newPage.setCode(response.statusCode());
            pageRepository.save(newPage);

            Map<String, Integer> lemmaCounts = lemmaFinder.getLemmas(htmlContent);
            for (Map.Entry<String, Integer> entry : lemmaCounts.entrySet()) {
                String lemmaStr = entry.getKey();
                int countOnPage = entry.getValue();

                Lemma lemmaRecord = lemmaRepository.findByLemmaAndSite(lemmaStr, site);
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

                MyIndex indexRecord = new MyIndex();
                indexRecord.setPage(newPage);
                indexRecord.setLemma(lemmaRecord);
                indexRecord.setRank(countOnPage);
                myIndexRepository.save(indexRecord);
            }

            site.setStatus(Status.INDEXED);
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
        } catch (Exception e) {
            site.setStatus(Status.FAILED);
            site.setLastError("Error indexing page: " + e.getMessage());
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
        }
    }

    @Transactional
    public void deletePageData(Page existingPage) {
        List<MyIndex> indexRecords = myIndexRepository.findByPage(existingPage);
        for (MyIndex record : indexRecords) {
            Lemma lemma = record.getLemma();
            lemma.setFrequency(lemma.getFrequency() - 1);
            if (lemma.getFrequency() <= 0) {
                lemmaRepository.delete(lemma);
            } else {
                lemmaRepository.save(lemma);
            }
        }
        myIndexRepository.deleteAllByPage(existingPage);
        pageRepository.delete(existingPage);
    }
}