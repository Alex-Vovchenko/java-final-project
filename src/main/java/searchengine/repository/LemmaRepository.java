package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import searchengine.model.Lemma;
import searchengine.model.Site;

import java.util.List;

public interface LemmaRepository extends JpaRepository<Lemma, Integer> {
    Lemma findByLemmaAndSite(String lemma, Site site);

    List<Lemma> findByLemma(String lemma);

    @Query("SELECT COUNT(l) FROM Lemma l")
    int countTotalLemmas();

    @Query("SELECT COUNT(l) FROM Lemma l WHERE l.site.url = :url")
    int countLemmasBySite(@Param("url") String url);

    @Query("SELECT l FROM Lemma l WHERE l.lemma = :lemma")
    Lemma findGlobalByLemma(@Param("lemma") String lemma);
}
