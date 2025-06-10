package searchengine.services;

import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class LemmaFinder {

    private static final List<String> EXCLUDE_POS = Arrays.asList("МЕЖД", "СОЮЗ", "ПРЕДЛ", "ЧАСТ");

    private final LuceneMorphology luceneMorphology;

    private final AtomicBoolean stopLemmaFinder = new AtomicBoolean(false);

    public LemmaFinder() throws Exception {
        luceneMorphology = new RussianLuceneMorphology();
    }

    public Map<String, Integer> getLemmas(String text) {
        String cleanedText = removeHtmlTags(text);
        Map<String, Integer> lemmaCounts = new HashMap<>();

        String[] words = cleanedText.toLowerCase(Locale.ROOT).split("[^а-яё]+");

        for (String word : words) {
            if (stopLemmaFinder.get()) {
                System.out.println("Lematization stopped on request.");
                return lemmaCounts;
            }
            if (word.isEmpty() || word.length() < 2) {
                continue;
            }

            List<String> morphInfos = luceneMorphology.getMorphInfo(word);
            boolean skip = false;
            for (String info : morphInfos) {
                for (String exclude : EXCLUDE_POS) {
                    if (info.contains(exclude)) {
                        skip = true;
                        break;
                    }
                }
                if (skip) {
                    break;
                }
            }
            if (skip) {
                continue;
            }

            List<String> normalForms = luceneMorphology.getNormalForms(word);
            if (normalForms.isEmpty()) {
                continue;
            }

            String lemma = normalForms.get(0);

            lemmaCounts.put(lemma, lemmaCounts.getOrDefault(lemma, 0) + 1);
        }
        return lemmaCounts;
    }

    public static String removeHtmlTags(String html) {
        if (html == null) {
            return "";
        }
        return Jsoup.parse(html).text();
    }

    public void stopLemma() {
        stopLemmaFinder.set(true);
    }

    public void startLemma() {
        stopLemmaFinder.set(false);
    }
}
