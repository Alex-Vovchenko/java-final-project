package searchengine.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.dto.search.SearchDataItem;
import searchengine.dto.search.SearchResponse;
import searchengine.model.Lemma;
import searchengine.model.MyIndex;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repository.LemmaRepository;
import searchengine.repository.MyIndexRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private final LemmaRepository lemmaRepository;
    private final PageRepository pageRepository;
    private final MyIndexRepository myIndexRepository;
    private final SiteRepository siteRepository;
    private final LemmaFinder lemmaFinder;

    private static final double thresholdPercentage = 0.7;

    public SearchService(LemmaRepository lemmaRepository,
                         PageRepository pageRepository,
                         MyIndexRepository myIndexRepository,
                         SiteRepository siteRepository,
                         LemmaFinder lemmaFinder) {
        this.lemmaRepository = lemmaRepository;
        this.pageRepository = pageRepository;
        this.myIndexRepository = myIndexRepository;
        this.siteRepository = siteRepository;
        this.lemmaFinder = lemmaFinder;
    }

    public boolean isIndexReady(String site) {
        return true;
    }

    @Transactional(readOnly = true)
    public SearchResponse search(String query, String siteUrl, int offset, int limit) {

        if (query == null || query.trim().isEmpty()) {
            return new SearchResponse(false, "An empty search query was specified", 0, null);
        }

        Site site = null;
        int totalPagesCount;
        if (siteUrl != null && !siteUrl.trim().isEmpty()) {
            site = siteRepository.findByUrl(siteUrl);
            if (site == null) {
                return new SearchResponse(false, "The site with the specified URL was not found", 0, null);
            }
            totalPagesCount = pageRepository.countPagesBySite(siteUrl);
        } else {
            totalPagesCount = pageRepository.countTotalPages();
        }
        int frequencyThreshold = (int) (totalPagesCount * thresholdPercentage);

        Map<String, Integer> rawLemmas = lemmaFinder.getLemmas(query);
        Set<String> uniqueLemmas = rawLemmas.keySet();
        if (uniqueLemmas.isEmpty()) {
            return new SearchResponse(false, "There are no valid words to search for.", 0, null);
        }

        List<String> filteredLemmas = new ArrayList<>();
        for (String lemma : uniqueLemmas) {
            Lemma lemmaRecord = getAggregatedLemma(lemma, site);
            if (lemmaRecord != null && lemmaRecord.getFrequency() <= frequencyThreshold) {
                filteredLemmas.add(lemma);
            }
        }
        if (filteredLemmas.isEmpty()) {
            List<SearchDataItem> emptyResults = new ArrayList<>();
            return new SearchResponse(true, null, 0, emptyResults);
        }

        final Site finalSite = site;
        filteredLemmas.sort(Comparator.comparingInt(lemma -> {
            Lemma rec = getAggregatedLemma(lemma, finalSite);
            return (rec != null ? rec.getFrequency() : Integer.MAX_VALUE);
        }));

        Map<Page, Double> pageRelevanceMap = new HashMap<>();
        String firstLemma = filteredLemmas.get(0);
        List<MyIndex> indicesForFirst = (finalSite != null) ?
                myIndexRepository.findByLemmaAndSite(firstLemma, finalSite) :
                myIndexRepository.findByLemma(firstLemma);
        for (MyIndex index : indicesForFirst) {
            Page page = index.getPage();
            pageRelevanceMap.put(page, (double) index.getRank());
        }

        for (int i = 1; i < filteredLemmas.size(); i++) {
            String lemma = filteredLemmas.get(i);
            List<MyIndex> indexesForLemma = (finalSite != null) ?
                    myIndexRepository.findByLemmaAndSite(lemma, finalSite) :
                    myIndexRepository.findByLemma(lemma);
            Map<Long, Integer> lemmaRanks = indexesForLemma.stream()
                    .collect(Collectors.toMap(
                            index -> index.getPage().getId().longValue(),
                            index -> Math.round(index.getRank())
                    ));
            Iterator<Map.Entry<Page, Double>> iterator = pageRelevanceMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Page, Double> entry = iterator.next();
                Page page = entry.getKey();
                if (lemmaRanks.containsKey(page.getId().longValue())) {
                    double newRank = entry.getValue() + lemmaRanks.get(page.getId().longValue());
                    entry.setValue(newRank);
                } else {
                    iterator.remove();
                }
            }
            if (pageRelevanceMap.isEmpty()) {
                break;
            }
        }

        if (pageRelevanceMap.isEmpty()) {
            List<SearchDataItem> emptyResults = new ArrayList<>();
            return new SearchResponse(true, null, 0, emptyResults);
        }

        double maxAbsRelevance = pageRelevanceMap.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);

        List<SearchDataItem> results = new ArrayList<>();
        for (Map.Entry<Page, Double> entry : pageRelevanceMap.entrySet()) {
            Page page = entry.getKey();
            double absRelevance = entry.getValue();
            double relRelevance = absRelevance / maxAbsRelevance;
            String title = extractTitle(page.getContent());
            String snippet = generateSnippet(page.getContent(), query);
            SearchDataItem item = new SearchDataItem();
            item.setSite(page.getSite().getUrl());
            item.setSiteName(page.getSite().getName());
            item.setUri(page.getPath());
            item.setTitle(title);
            item.setSnippet(snippet);
            item.setRelevance(relRelevance);
            results.add(item);
        }

        results.sort((r1, r2) -> Double.compare(r2.getRelevance(), r1.getRelevance()));

        int totalCount = results.size();
        int toIndex = Math.min(offset + limit, totalCount);
        List<SearchDataItem> pagedResults = (offset >= totalCount)
                ? new ArrayList<>()
                : results.subList(offset, toIndex);

        return new SearchResponse(true, null, totalCount, pagedResults);
    }


    private Lemma getAggregatedLemma(String lemma, Site site) {
        if (site != null) {
            return lemmaRepository.findByLemmaAndSite(lemma, site);
        } else {
            List<Lemma> lemmaRecords = lemmaRepository.findByLemma(lemma);
            if (lemmaRecords == null || lemmaRecords.isEmpty()) {
                return null;
            }
            int totalFrequency = lemmaRecords.stream().mapToInt(Lemma::getFrequency).sum();
            Lemma aggregated = new Lemma();
            aggregated.setLemma(lemma);
            aggregated.setFrequency(totalFrequency);
            return aggregated;
        }
    }

    private String extractTitle(String content) {
        try {
            return org.jsoup.Jsoup.parse(content).title();
        } catch (Exception e) {
            return "";
        }
    }

    private String generateSnippet(String content, String query) {
        String[] queryWords = query.split("\\s+");
        String plainText = org.jsoup.Jsoup.parse(content).text();
        int snippetLength = 200;
        int matchPos = -1;
        for (String word : queryWords) {
            matchPos = plainText.toLowerCase().indexOf(word.toLowerCase());
            if (matchPos >= 0) {
                break;
            }
        }
        if (matchPos < 0) {
            matchPos = 0;
        }
        int start = Math.max(matchPos - 50, 0);
        int end = Math.min(start + snippetLength, plainText.length());
        String snippet = plainText.substring(start, end);
        for (String word : queryWords) {
            snippet = snippet.replaceAll("(?i)(" + word + ")", "<b>$1</b>");
        }
        return snippet;
    }
}