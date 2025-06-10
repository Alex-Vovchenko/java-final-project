package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repository.LemmaRepository;
import searchengine.repository.MyIndexRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SiteService {
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final MyIndexRepository myIndexRepository;
    private final LemmaRepository lemmaRepository;

    @Transactional
    public void deleteSiteData(String siteDetached) {

        Site site = siteRepository.findByUrl(siteDetached);
        List<Page> pages = site.getPages();
        if (pages != null) {
            for (Page page : pages) {
                myIndexRepository.deleteAllByPage(page);
            }
        }

        if (pages != null) {
            for (Page page : pages) {
                pageRepository.delete(page);
            }
        }

        List<Lemma> lemmas = site.getLemmas();
        if (lemmas != null) {
            lemmaRepository.deleteAll(lemmas);
        }

        siteRepository.delete(site);
    }
}
