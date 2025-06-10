package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.MyIndex;
import searchengine.model.Page;
import searchengine.model.Site;

import java.util.List;

public interface MyIndexRepository extends JpaRepository<MyIndex, Integer> {

    List<MyIndex> findByPage(Page page);

    @Query("SELECT mi FROM MyIndex mi WHERE mi.lemma.lemma = :lemma")
    List<MyIndex> findByLemma(@Param("lemma") String lemma);

    @Query("SELECT mi FROM MyIndex mi WHERE mi.lemma.lemma = :lemma AND mi.page.site = :site")
    List<MyIndex> findByLemmaAndSite(@Param("lemma") String lemma, @Param("site") Site site);

    @Modifying
    @Transactional
    void deleteAllByPage(Page page);
}
