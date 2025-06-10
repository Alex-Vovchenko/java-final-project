package searchengine.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.search.SearchResponse;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.IndexingService;
import searchengine.services.SearchService;
import searchengine.services.StatisticsService;
import searchengine.dto.indexing.*;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final StatisticsService statisticsService;
    private final IndexingService indexingService;
    private final SearchService searchService;

    public ApiController(StatisticsService statisticsService, IndexingService indexingService, SearchService searchService) {
        this.statisticsService = statisticsService;
        this.indexingService = indexingService;
        this.searchService = searchService;
    }

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping(value = "/startIndexing", produces = "application/json")
    public ResponseEntity<IndexingResponse> startIndexingResponse() {
        if (!indexingService.startIndexingResponse()) {
            return ResponseEntity.badRequest().body(new IndexingResponse(false, "Indexing has already started"));
        }
        return ResponseEntity.ok(new IndexingResponse(true));
    }

    @GetMapping(value = "/stopIndexing", produces = "application/json")
    public ResponseEntity<IndexingResponse> stopIndexing() {
        if (!indexingService.stopIndexing()) {
            return ResponseEntity.badRequest().body(new IndexingResponse(false, "Indexing not started"));
        }
        return ResponseEntity.ok(new IndexingResponse(true));
    }

    @PostMapping(value = "/indexPage")
    public ResponseEntity<IndexingResponse> indexPage(@RequestParam String url) {
        IndexingResponse response = indexingService.indexPageResponse(url);
        if (response.isResult()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "site", required = false) String site,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {

        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(new SearchResponse(false, "An empty search query was specified.", 0, null));
        }

        if (!searchService.isIndexReady(site)) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(new SearchResponse(false, "Search not available, indexing not complete", 0, null));
        }

        SearchResponse response = searchService.search(query, site, offset, limit);
        return ResponseEntity.ok(response);
    }
}
