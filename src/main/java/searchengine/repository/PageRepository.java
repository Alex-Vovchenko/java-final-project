package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import searchengine.model.Page;
import searchengine.model.Site;

public interface PageRepository extends JpaRepository<Page, Integer> {

    boolean existsBySiteIdAndPath(Integer siteId, String path);

    Page findByPathAndSite(String path, Site site);

    @Query("SELECT COUNT(p) FROM Page p")
    int countTotalPages();

    @Query("SELECT COUNT(p) FROM Page p WHERE p.site.url = :url")
    int countPagesBySite(@Param("url") String url);
}
