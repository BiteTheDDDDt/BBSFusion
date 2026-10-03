package dev.bbsfusion.core;

import java.net.URI;

/** Resolves HTTP references while preserving the path for query-only locations. */
public final class HttpUrlResolver {
    private HttpUrlResolver() {
    }

    public static URI resolve(URI base, String reference) {
        URI relative = URI.create(reference);
        if (relative.getScheme() == null && relative.getRawAuthority() == null
                && relative.getRawPath() != null && relative.getRawPath().isEmpty()) {
            // URI.resolve treats ?query (and an empty reference) as a sibling
            // resource on some Java/Android versions instead of this resource.
            String target = base.toString();
            int fragment = target.indexOf('#');
            if (fragment >= 0) {
                target = target.substring(0, fragment);
            }
            if (relative.getRawQuery() != null) {
                int query = target.indexOf('?');
                if (query >= 0) {
                    target = target.substring(0, query);
                }
                target += "?" + relative.getRawQuery();
            }
            if (relative.getRawFragment() != null) {
                target += "#" + relative.getRawFragment();
            }
            return URI.create(target);
        }
        return base.resolve(relative);
    }
}
