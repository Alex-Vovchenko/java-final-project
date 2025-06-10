package searchengine.services;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

@Component
public class DomainUrlVerification {
    public static boolean isSameDomain(String baseDomain, String thisLink) {
        try {
            URI baseUri = new URI(baseDomain);
            URI linkUri = new URI(thisLink);

            String baseHost = baseUri.getHost();
            String linkHost = linkUri.getHost();

            if (baseHost == null || linkHost == null) {
                return false;
            }

            baseHost = baseHost.startsWith("www.") ? baseHost.substring(4) : baseHost;
            linkHost = linkHost.startsWith("www.") ? linkHost.substring(4) : linkHost;

            baseHost = baseHost.toLowerCase();
            linkHost = linkHost.toLowerCase();

            return baseHost.equals(linkHost);
        } catch (URISyntaxException e) {
            System.out.println("Error comparing domains: " + e.getMessage());
            return false;
        }
    }

    public static boolean isValidURL(String url) {
        try {
            new URI(url);
            return true;
        } catch (URISyntaxException e) {
            System.out.println("Invalid URL: " + url);
            return false;
        }
    }
}
