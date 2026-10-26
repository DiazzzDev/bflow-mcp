package bflow.mcp.security;

import java.io.IOException;
import java.net.URI;

/**
 * Downloads one client metadata document. A seam so tests can supply
 * documents without network access; the production implementation is
 * {@link JdkCimdDocumentFetcher}.
 */
@FunctionalInterface
public interface CimdDocumentFetcher {

    /**
     * @param uri the allowlisted {@code client_id} URL to download.
     * @return the response body, as text.
     * @throws IOException if the document cannot be fetched or is not
     *      acceptable (bad status, redirect, wrong content type, too large).
     */
    String fetch(URI uri) throws IOException;
}
